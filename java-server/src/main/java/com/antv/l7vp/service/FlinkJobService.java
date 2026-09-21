package com.antv.l7vp.service;

import com.antv.l7vp.config.FlinkJobProperties;
import com.antv.l7vp.config.StreamProperties;
import com.antv.l7vp.model.Dataset;
import com.antv.l7vp.repository.DatasetRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Flink 流式作业提交客户端。
 *
 * 职责：
 * - 运行期把内嵌在 server jar 里的作业 jar（classpath flink-job/l7vp-stream-job.jar）解出，
 *   经 Flink REST API 上传并提交作业，绑定某个数据集；
 * - 按作业名 {@code l7vp-stream-<datasetId>} 幂等去重、查询状态、取消；
 * - 供删除联动：删除数据集/项目时把对应运行中作业取消。
 *
 * Flink REST：
 *   POST /jars/upload         上传（multipart，part=jarfile）→ { filename }
 *   POST /jars/{jarId}/run    提交  → { jobid }
 *   GET  /jobs/overview       作业总览（按 name 匹配）
 *   PATCH /jobs/{jobId}?mode=cancel   取消（个别版本退化为 GET /jobs/{jobId}/cancel）
 *
 * 回推地址 = flink.push.base-url + /api/projects/{pid}/datasets/{did}/stream/push
 * （该 push 端点已实现，X-Stream-Token 校验由 flink.push.token 决定）
 */
@Service
public class FlinkJobService {

    private static final Logger log = LoggerFactory.getLogger(FlinkJobService.class);

    private static final Set<String> RUNNING_STATES = new HashSet<>(Arrays.asList("RUNNING", "RESTARTING", "RECONCILING"));

    private final RestTemplate restTemplate;
    private File jobJarCache;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private FlinkJobProperties properties;

    @Autowired
    private StreamProperties streamProperties;

    @Autowired
    private ObjectMapper objectMapper;

    public FlinkJobService() {
        // 用 Apache HttpClient：JDK HttpURLConnection 不支持 PATCH，而 Flink 取消作业走 PATCH /jobs/{jid}?mode=cancel。
        // 保留连接/读超时，避免集群不可达时阻塞。
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(10000);
        this.restTemplate = new RestTemplate(factory);
    }

    // ============ 对外 API ============

    /**
     * 幂等提交/复用作业。topic 等可经 overrides 覆盖，否则读数据集 metadata.kafka，再取配置兜底。
     */
    public Map<String, Object> startJob(String projectId, String datasetId, Map<String, Object> overrides) {
        Dataset dataset = datasetRepository.findById(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("数据集不存在: " + datasetId);
        }
        if (projectId != null && !projectId.isEmpty() && !projectId.equals(dataset.getProjectId())) {
            throw new IllegalArgumentException("数据集不属于该项目: " + projectId);
        }

        Map<String, Object> kafka = readKafkaMeta(dataset);

        // 键化模式（每船最新位置）：读数据集顶层 metadata.streamKey / metadata.maxWindow。
        // 仅当 streamKey 非空才在作业上启用 keyBy 聚合 + 全量船表 replace 回推；否则维持原始 append。
        Map<String, Object> meta = parseMeta(dataset.getMetadata());
        String streamKey = overrideString(overrides, "streamKey");
        if (streamKey == null && meta != null) {
            streamKey = str(meta.get("streamKey"));
        }
        int fleetMax = streamProperties.getStream().getMaxWindow();
        if (meta != null && meta.get("maxWindow") instanceof Number) {
            fleetMax = ((Number) meta.get("maxWindow")).intValue();
        }

        String topic = overrideString(overrides, "topic");
        if (topic == null) {
            topic = kafka != null ? str(kafka.get("topic")) : null;
        }
        if (topic == null || topic.isEmpty()) {
            throw new IllegalArgumentException("缺少 Kafka topic：请在数据集 metadata.kafka.topic 或请求体配置");
        }

        String bootstrap = overrideString(overrides, "bootstrapServers");
        if (bootstrap == null) {
            bootstrap = kafka != null ? str(kafka.get("bootstrapServers")) : null;
        }
        if (bootstrap == null || bootstrap.isEmpty()) {
            bootstrap = properties.getKafka().getBootstrapServers();
        }
        if (bootstrap == null || bootstrap.isEmpty()) {
            throw new IllegalArgumentException("缺少 Kafka bootstrapServers：请在数据集 metadata.kafka.bootstrapServers、请求体或 flink.kafka.bootstrap-servers 配置");
        }

        int parallelism = overrideInt(overrides, "parallelism", properties.getJob().getParallelism());
        int batchSize = overrideInt(overrides, "batchSize", properties.getJob().getBatchSize());
        long batchMs = overrideLong(overrides, "batchMs", properties.getJob().getBatchMs());
        if (parallelism < 1) {
            parallelism = 1;
        }

        String name = jobName(datasetId);
        String existingJobId;
        try {
            existingJobId = findRunningJobId(datasetId);
        } catch (IOException e) {
            // 集群不可达时无法做幂等去重，直接给出明确错误（后续上传/提交同样会失败）
            throw new RuntimeException("无法连接 Flink 集群检查已有作业: " + e.getMessage(), e);
        }
        if (existingJobId != null) {
            log.info("[FLINK_JOB] 数据集 {} 已有运行作业 {}，复用（幂等）", datasetId, existingJobId);
            return buildResult(projectId, datasetId, topic, parallelism, name, existingJobId, "RUNNING", true);
        }

        File jar;
        String jarId;
        try {
            jar = resolveJobJar();
            jarId = uploadJar(jar);
        } catch (Exception e) {
            throw new RuntimeException("Flink 作业 jar 上传失败: " + e.getMessage(), e);
        }

        String pushUrl = trimSlash(properties.getPush().getBaseUrl())
            + "/api/projects/" + projectId + "/datasets/" + datasetId + "/stream/push";
        List<String> prog = new ArrayList<>();
        prog.add("projectId=" + projectId);
        prog.add("datasetId=" + datasetId);
        prog.add("topic=" + topic);
        prog.add("bootstrapServers=" + bootstrap);
        prog.add("groupId=" + properties.getKafka().getGroupIdPrefix() + "-" + datasetId);
        prog.add("pushUrl=" + pushUrl);
        prog.add("pushToken=" + (properties.getPush().getToken() == null ? "" : properties.getPush().getToken()));
        prog.add("batchSize=" + batchSize);
        prog.add("batchMs=" + batchMs);
        // 键化模式：把标识字段与船表容量上限注入作业（与后端环形缓冲 maxWindow 解析口径一致）
        if (streamKey != null && !streamKey.isEmpty()) {
            prog.add("streamKey=" + streamKey.trim());
            prog.add("maxWindow=" + Math.max(1, fleetMax));
        }
        // 消费起始位置：优先 overrides.autoOffsetReset，否则 flink.kafka.auto-offset-reset 配置(默认 latest)
        String autoOffsetReset = overrideString(overrides, "autoOffsetReset");
        if (autoOffsetReset == null || autoOffsetReset.isEmpty()) {
            autoOffsetReset = properties.getKafka().getAutoOffsetReset();
        }
        if (autoOffsetReset != null && !autoOffsetReset.isEmpty()) {
            prog.add("autoOffsetReset=" + autoOffsetReset.trim().toLowerCase());
        }
        // 落时序库（TimescaleDB/PostgreSQL）：flink.tsdb.enabled=true 且 url 非空才传给作业
        FlinkJobProperties.Tsdb tsdb = properties.getTsdb();
        if (tsdb.isEnabled() && tsdb.getUrl() != null && !tsdb.getUrl().isEmpty()) {
            prog.add("tsdbEnabled=true");
            prog.add("tsdbUrl=" + tsdb.getUrl());
            prog.add("tsdbUser=" + (tsdb.getUser() == null ? "" : tsdb.getUser()));
            prog.add("tsdbPassword=" + (tsdb.getPassword() == null ? "" : tsdb.getPassword()));
            prog.add("tsdbTable=" + (tsdb.getTable() == null || tsdb.getTable().isEmpty() ? "stream_events" : tsdb.getTable()));
            prog.add("tsdbTimeField=" + (tsdb.getTimeField() == null ? "" : tsdb.getTimeField()));
        }
        String progArgs = String.join(" ", prog);

        String jobId;
        try {
            jobId = runJob(jarId, progArgs, parallelism);
        } catch (Exception e) {
            throw new RuntimeException("Flink 作业提交失败: " + e.getMessage(), e);
        }
        log.info("[FLINK_JOB] 数据集 {} 作业已提交 jobId={} topic={}", datasetId, jobId, topic);
        return buildResult(projectId, datasetId, topic, parallelism, name, jobId, "RUNNING", false);
    }

    /** 查询某数据集作业状态（集群不可达时返回 CLUSTER_UNREACHABLE，不抛异常） */
    public Map<String, Object> status(String datasetId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("name", jobName(datasetId));
        try {
            String jobId = findRunningJobId(datasetId);
            if (jobId == null) {
                result.put("state", "NOT_RUNNING");
            } else {
                result.put("state", "RUNNING");
                result.put("jobId", jobId);
            }
        } catch (Exception e) {
            result.put("state", "CLUSTER_UNREACHABLE");
            result.put("error", e.getMessage());
        }
        return result;
    }

    /** 取消某数据集的作业（不存在/集群不可达仅告警，不抛异常，便于删除联动） */
    public void stop(String datasetId) {
        try {
            String jobId = findRunningJobId(datasetId);
            if (jobId == null || jobId.trim().isEmpty()) {
                log.info("[FLINK_JOB] 数据集 {} 无运行中作业，跳过取消", datasetId);
                return;
            }
            cancelJob(jobId);
            log.info("[FLINK_JOB] 数据集 {} 作业 {} 已取消", datasetId, jobId);
        } catch (Exception e) {
            log.warn("[FLINK_JOB] 取消数据集 {} 作业失败: {}", datasetId, e.getMessage());
        }
    }

    /** 数据集删除联动：type=local 且 metadata.stream=true 的数据集即尝试取消作业（普通数据集零集群开销） */
    public void stopIfStreaming(String datasetId) {
        Dataset dataset = datasetRepository.findById(datasetId);
        if (dataset == null) {
            return;
        }
        Map<String, Object> meta = parseMeta(dataset.getMetadata());
        if (meta == null || !Boolean.TRUE.equals(meta.get("stream"))) {
            return;
        }
        stop(datasetId);
    }

    /** 判断数据集是否可重新加载（同 auto-start 门槛：stream=true 且 kafka.topic 非空且未显式关闭）。供 reload 接口在清空缓冲前校验。 */
    public boolean isStreamingKafkaDataset(String datasetId) {
        Dataset dataset = datasetRepository.findById(datasetId);
        return dataset != null && isStreamDataset(dataset);
    }

    /** 项目删除联动：取消该项目所有流式数据集作业 */
    public void stopProjectStreamJobs(String projectId) {
        List<Dataset> datasets = datasetRepository.findByProjectId(projectId);
        for (Dataset ds : datasets) {
            stopIfStreaming(ds.getDatasetId());
        }
    }

    /** 保存项目联动：flink.auto-start=true 时，为含 kafka 配置的流式数据集幂等提交作业；单数据集失败不阻塞保存 */
    public void startProjectStreamJobsIfAuto(String projectId) {
        if (!properties.isAutoStart()) {
            return;
        }
        List<Dataset> datasets = datasetRepository.findByProjectId(projectId);
        for (Dataset ds : datasets) {
            if (!isStreamDataset(ds)) {
                continue;
            }
            try {
                startJob(projectId, ds.getDatasetId(), null);
            } catch (Exception e) {
                log.warn("[FLINK_JOB] auto-start 失败 datasetId={}, err={}", ds.getDatasetId(), e.getMessage());
            }
        }
    }

    /** 判断是否 metadata.stream=true 且 metadata.kafka.topic 非空且未显式关闭（kafka.enabled / enabled=false）的流式数据集 */
    private boolean isStreamDataset(Dataset ds) {
        Map<String, Object> meta = parseMeta(ds.getMetadata());
        if (meta == null || !Boolean.TRUE.equals(meta.get("stream"))) {
            return false;
        }
        Map<String, Object> kafka = readKafkaMeta(ds);
        if (kafka == null) {
            return false;
        }
        Object enabled = kafka.containsKey("enabled") ? kafka.get("enabled") : meta.get("enabled");
        if (enabled != null && Boolean.FALSE.equals(enabled)) {
            return false;
        }
        Object topic = kafka.get("topic");
        return topic != null && !topic.toString().isEmpty();
    }

    // ============ 内部实现 ============

    private Map<String, Object> buildResult(String projectId, String datasetId, String topic,
                                            int parallelism, String name, String jobId, String state, boolean reused) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", projectId);
        result.put("datasetId", datasetId);
        result.put("topic", topic);
        result.put("parallelism", parallelism);
        result.put("name", name);
        result.put("jobId", jobId);
        result.put("state", state);
        result.put("reused", reused);
        return result;
    }

    private String jobName(String datasetId) {
        return "l7vp-stream-" + datasetId;
    }

    /** 在集群 overview 中按作业名查找运行中作业的 jobId；无则返回 null（集群不可达时抛异常） */
    private String findRunningJobId(String datasetId) throws IOException {
        String url = baseUrl() + "/jobs/overview";
        String body = restTemplate.getForEntity(url, String.class).getBody();
        JsonNode root = objectMapper.readTree(body);
        JsonNode jobs = root.path("jobs");
        String targetName = jobName(datasetId);
        if (jobs.isArray()) {
            for (JsonNode job : jobs) {
                String state = job.path("state").asText("");
                if (RUNNING_STATES.contains(state) && targetName.equals(job.path("name").asText(""))) {
                    // Flink /jobs/overview 的作业 id 字段是 jid（不是 id）——读 jid，缺省再退回 id
                    String jid = job.path("jid").asText("");
                    if (jid.isEmpty()) {
                        jid = job.path("id").asText("");
                    }
                    if (!jid.isEmpty()) {
                        return jid;
                    }
                }
            }
        }
        return null;
    }

    private File resolveJobJar() throws IOException {
        String external = properties.getJob().getJarFilePath();
        if (external != null && !external.isEmpty()) {
            File f = new File(external);
            if (!f.isFile()) {
                throw new IOException("外部作业 jar 不存在: " + external);
            }
            return f;
        }
        if (jobJarCache != null && jobJarCache.isFile()) {
            return jobJarCache;
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("flink-job/l7vp-stream-job.jar")) {
            if (in == null) {
                throw new IOException("classpath 内未找到 flink-job/l7vp-stream-job.jar。请用 mvn package 构建(antrun 自动内嵌)，或配置 flink.job.jar-file-path");
            }
            File tmp = File.createTempFile("l7vp-stream-job-", ".jar");
            Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            tmp.deleteOnExit();
            jobJarCache = tmp;
            return tmp;
        }
    }

    private String uploadJar(File jar) throws IOException {
        String url = baseUrl() + "/jars/upload";
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("jarfile", new FileSystemResource(jar));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(parts, headers), String.class);
        JsonNode root = objectMapper.readTree(resp.getBody());
        String filename = root.path("filename").asText("");
        int idx = filename.lastIndexOf('/');
        String jarId = idx >= 0 ? filename.substring(idx + 1) : filename;
        if (jarId.isEmpty()) {
            throw new IOException("Flink upload 未返回 jarId, resp=" + resp.getBody());
        }
        return jarId;
    }

    private String runJob(String jarId, String progArgs, int parallelism) throws IOException {
        String url = baseUrl() + "/jars/" + jarId + "/run";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entryClass", properties.getJob().getEntryClass());
        body.put("programArgs", progArgs);
        body.put("parallelism", parallelism);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        JsonNode root = objectMapper.readTree(resp.getBody());
        String jobId = root.path("jobid").asText("");
        if (jobId.isEmpty()) {
            throw new IOException("Flink run 未返回 jobid, resp=" + resp.getBody());
        }
        return jobId;
    }

    private void cancelJob(String jobId) {
        if (jobId == null || jobId.trim().isEmpty()) {
            throw new RuntimeException("取消 Flink 作业失败 jobId 为空");
        }
        String url = baseUrl() + "/jobs/" + jobId + "?mode=cancel";
        try {
            restTemplate.exchange(url, HttpMethod.PATCH, HttpEntity.EMPTY, String.class);
            return;
        } catch (Exception e1) {
            // 兼容旧 REST 取消接口
            try {
                restTemplate.getForEntity(baseUrl() + "/jobs/" + jobId + "/cancel", String.class);
                return;
            } catch (Exception e2) {
                throw new RuntimeException("取消 Flink 作业失败 jobId=" + jobId + ": " + e2.getMessage(), e2);
            }
        }
    }

    // ============ 小工具 ============

    private String baseUrl() {
        return trimSlash(properties.getCluster().getUrl());
    }

    private static String trimSlash(String s) {
        if (s == null) {
            return "";
        }
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readKafkaMeta(Dataset dataset) {
        Map<String, Object> meta = parseMeta(dataset.getMetadata());
        if (meta == null) {
            return null;
        }
        Object kafkaObj = meta.get("kafka");
        if (kafkaObj instanceof Map) {
            return (Map<String, Object>) kafkaObj;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMeta(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(metadata, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String overrideString(Map<String, Object> overrides, String key) {
        if (overrides == null) {
            return null;
        }
        Object v = overrides.get(key);
        return v == null ? null : v.toString();
    }

    private static int overrideInt(Map<String, Object> overrides, String key, int def) {
        if (overrides == null) {
            return def;
        }
        Object v = overrides.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String && !((String) v).isEmpty()) {
            try {
                return Integer.parseInt((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static long overrideLong(Map<String, Object> overrides, String key, long def) {
        if (overrides == null) {
            return def;
        }
        Object v = overrides.get(key);
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        if (v instanceof String && !((String) v).isEmpty()) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
