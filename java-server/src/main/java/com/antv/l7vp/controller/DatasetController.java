package com.antv.l7vp.controller;

import com.antv.l7vp.config.StreamProperties;
import com.antv.l7vp.dto.CreateDatasetRequest;
import com.antv.l7vp.dto.CreateDatasetResult;
import com.antv.l7vp.dto.PagedRows;
import com.antv.l7vp.dto.StreamPushRequest;
import com.antv.l7vp.service.DatasetService;
import com.antv.l7vp.service.FlinkJobService;
import com.antv.l7vp.service.StreamHistoryService;
import com.antv.l7vp.service.StreamSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DatasetController {

    private static final Logger log = LoggerFactory.getLogger(DatasetController.class);

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private StreamSessionManager streamSessionManager;

    @Autowired
    private StreamProperties streamProperties;

    @Autowired
    private FlinkJobService flinkJobService;

    @Autowired
    private StreamHistoryService streamHistoryService;

    @PostMapping("/projects/{projectId}/datasets/upload")
    public CreateDatasetResult uploadDataset(
            @PathVariable String projectId,
            @RequestBody CreateDatasetRequest request) {
        return datasetService.createDatasetWithRows(projectId, request);
    }

    @GetMapping("/projects/{projectId}/datasets/{datasetId}/rows")
    public PagedRows getRows(
            @PathVariable String projectId,
            @PathVariable String datasetId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "500") int size) {
        return datasetService.getRows(datasetId, page, size);
    }

    @DeleteMapping("/projects/{projectId}/datasets/{datasetId}")
    public ResponseEntity<Void> deleteDataset(
            @PathVariable String projectId,
            @PathVariable String datasetId) {
        try {
            datasetService.deleteDataset(datasetId);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * Flink 推送流式数据到指定数据集（不入库，仅进内存环形缓冲并广播给前端订阅者）。
     *
     * POST /api/projects/{projectId}/datasets/{datasetId}/stream/push
     * Header: X-Stream-Token（当 l7vp.stream.push-token 配置非空时必填，值需匹配）
     * Body: { rows: [{...}], op: "append" | "replace" }
     */
    @PostMapping("/projects/{projectId}/datasets/{datasetId}/stream/push")
    public ResponseEntity<?> pushStreamData(
            @PathVariable String projectId,
            @PathVariable String datasetId,
            @RequestHeader(value = "X-Stream-Token", required = false) String token,
            @RequestBody StreamPushRequest request) {
        // 鉴权：仅当配置了 push-token 时强制校验
        String cfgToken = streamProperties.getStream().getPushToken();
        if (cfgToken != null && !cfgToken.isEmpty()) {
            if (token == null || !token.equals(cfgToken)) {
                log.warn("[STREAM_PUSH] 鉴权失败 datasetId={}", datasetId);
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(errorBody("推送令牌无效"));
            }
        }
        // 数据集必须存在
        if (!datasetService.exists(datasetId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("数据集不存在: " + datasetId));
        }
        String op = (request.getOp() == null || request.getOp().isEmpty()) ? "append" : request.getOp();
        int pushed = streamSessionManager.push(datasetId, request.getRows(), op);
        int subscribers = streamSessionManager.getSubscriberCount(datasetId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("pushed", pushed);
        result.put("op", op);
        result.put("subscribers", subscribers);
        return ResponseEntity.ok(result);
    }

    /**
     * 幂等启动该数据集的 Flink 流式作业（Kafka → 加工 → 批量回推本 push 端点）。
     *
     * POST /api/projects/{projectId}/datasets/{datasetId}/stream/job/start
     * Body(可选): { topic, bootstrapServers, parallelism, batchSize, batchMs }
     * 未在 body 提供时读数据集 metadata.kafka，再取 flink.kafka.* 配置兜底。已在运行的作业直接复用。
     */
    @PostMapping("/projects/{projectId}/datasets/{datasetId}/stream/job/start")
    public ResponseEntity<?> startStreamJob(
            @PathVariable String projectId,
            @PathVariable String datasetId,
            @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> result = flinkJobService.startJob(projectId, datasetId, body);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[STREAM_JOB] start failed datasetId={}", datasetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /** 查询该数据集 Flink 作业状态（集群不可达返回 CLUSTER_UNREACHABLE） */
    @GetMapping("/projects/{projectId}/datasets/{datasetId}/stream/job/status")
    public ResponseEntity<?> streamJobStatus(
            @PathVariable String projectId,
            @PathVariable String datasetId) {
        try {
            return ResponseEntity.ok(flinkJobService.status(datasetId));
        } catch (Exception e) {
            log.warn("[STREAM_JOB] status failed datasetId={}", datasetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /** 取消该数据集的 Flink 作业（未运行/集群不可达视为成功，不抛错） */
    @DeleteMapping("/projects/{projectId}/datasets/{datasetId}/stream/job/stop")
    public ResponseEntity<?> stopStreamJob(
            @PathVariable String projectId,
            @PathVariable String datasetId) {
        try {
            flinkJobService.stop(datasetId);
            // stop() 对取消失败是 best-effort（吞异常记日志）；取消是异步的(RUNNING→CANCELING→CANCELED)，
            // 这里短轮询到确认为 NOT_RUNNING 才返回 stopped:true，避免「接口报已停、作业却还在跑」或「其实已停但复查太早」两类误报。
            String state = "RUNNING";
            Object jobId = null;
            for (int i = 0; i < 10; i++) {
                Map<String, Object> st = flinkJobService.status(datasetId);
                state = String.valueOf(st.get("state"));
                jobId = st.get("jobId");
                if ("NOT_RUNNING".equals(state) || "CLUSTER_UNREACHABLE".equals(state)) {
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if ("NOT_RUNNING".equals(state)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("datasetId", datasetId);
                result.put("stopped", true);
                return ResponseEntity.ok(result);
            }
            log.warn("[STREAM_JOB] stop 后作业仍在/无法确认 datasetId={} state={} jobId={}", datasetId, state, jobId);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(
                    "作业取消失败或无法确认：数据集 " + datasetId + " 状态=" + state
                            + (jobId != null ? ", jobId=" + jobId : "")));
        } catch (Exception e) {
            log.warn("[STREAM_JOB] stop failed datasetId={}", datasetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /**
     * 重载流式数据：先取消当前 Flink 作业并确认停止 → 清空内存缓冲并向所有订阅者广播空帧（前端立即清屏）
     * → 重新提交作业，因作业未开 checkpoint、消费组未提交 offset，会从 Kafka earliest 重读 topic 完整重新加载。
     * 仅适用于 metadata.stream=true 且已配置 kafka.topic 的流式数据集，否则 400（避免先清空后才发现无法启动）。
     *
     * POST /api/projects/{projectId}/datasets/{datasetId}/stream/reload
     */
    @PostMapping("/projects/{projectId}/datasets/{datasetId}/stream/reload")
    public ResponseEntity<?> reloadStreamData(
            @PathVariable String projectId,
            @PathVariable String datasetId) {
        try {
            if (!flinkJobService.isStreamingKafkaDataset(datasetId)) {
                return ResponseEntity.badRequest().body(errorBody(
                        "非流式数据集或未配置 Kafka topic，无法重新加载：" + datasetId));
            }
            // 1) 取消当前作业
            flinkJobService.stop(datasetId);
            // 2) 短轮询确认停止（取消是异步的 RUNNING→CANCELING→CANCELED，避免与重启竞态重复消费）
            String state = "RUNNING";
            for (int i = 0; i < 10; i++) {
                Map<String, Object> st = flinkJobService.status(datasetId);
                state = String.valueOf(st.get("state"));
                if ("NOT_RUNNING".equals(state) || "CLUSTER_UNREACHABLE".equals(state)) {
                    break;
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (!"NOT_RUNNING".equals(state)) {
                log.warn("[STREAM_JOB] reload stop 后作业未确认停止 state={} datasetId={}", state, datasetId);
            }
            // 3) 清空缓冲并广播空帧（在作业已停止后清，避免被旧作业推入的新行回填）
            int clearedSubscribers = streamSessionManager.clear(datasetId);
            // 4) 重新提交作业（从 earliest 重读，完整重新加载）
            Map<String, Object> result = flinkJobService.startJob(projectId, datasetId, null);
            result.put("reload", true);
            result.put("clearedSubscribers", clearedSubscribers);
            result.put("stoppedState", state);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[STREAM_JOB] reload failed datasetId={}", datasetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /**
     * 历史轨迹：从时序库取某个目标（流式图标）的全部历史点，按时间升序。
     * 目标标识字段取自数据集 metadata.streamKey（服务端读取，不信客户端传的字段名）。
     *
     * GET /api/projects/{projectId}/datasets/{datasetId}/stream/history?key=&start=&end=&limit=
     *   key   必填，目标标识值（如 mmsi）
     *   start/end 可选，epoch 毫秒
     *   limit 可选，单次最多返回行数（上限见 flink.tsdb.history-max-rows）
     */
    @GetMapping("/projects/{projectId}/datasets/{datasetId}/stream/history")
    public ResponseEntity<?> streamHistory(
            @PathVariable String projectId,
            @PathVariable String datasetId,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) Long start,
            @RequestParam(required = false) Long end,
            @RequestParam(required = false) Integer limit) {
        try {
            StreamHistoryService.HistoryResult result =
                    streamHistoryService.queryHistory(projectId, datasetId, key, start, end, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", result.getKey());
            body.put("keyField", result.getKeyField());
            body.put("rows", result.getRows());
            body.put("rowCount", result.getRowCount());
            body.put("truncated", result.isTruncated());
            return ResponseEntity.ok(body);
        } catch (StreamHistoryService.DatasetNotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
        } catch (StreamHistoryService.TsdbUnavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[STREAM_HISTORY] failed datasetId={} key={}", datasetId, key, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /**
     * 该数据集在时序库里的实际数据时间窗（min/max event_time），用于前端时间选择框默认值与提示。
     *
     * GET /api/projects/{projectId}/datasets/{datasetId}/stream/history/range
     */
    @GetMapping("/projects/{projectId}/datasets/{datasetId}/stream/history/range")
    public ResponseEntity<?> streamHistoryRange(
            @PathVariable String projectId,
            @PathVariable String datasetId) {
        try {
            return ResponseEntity.ok(streamHistoryService.queryRange(projectId, datasetId));
        } catch (StreamHistoryService.DatasetNotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
        } catch (StreamHistoryService.TsdbUnavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[STREAM_HISTORY] range failed datasetId={}", datasetId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", msg);
        return err;
    }
}
