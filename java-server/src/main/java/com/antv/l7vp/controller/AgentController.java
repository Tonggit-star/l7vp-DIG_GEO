package com.antv.l7vp.controller;

import com.antv.l7vp.model.DatasetColumn;
import com.antv.l7vp.model.Layer;
import com.antv.l7vp.model.Project;
import com.antv.l7vp.repository.DatasetColumnRepository;
import com.antv.l7vp.repository.LayerRepository;
import com.antv.l7vp.repository.ProjectRepository;
import com.antv.l7vp.service.AgentCommandService;
import com.antv.l7vp.service.RegionService;
import com.antv.l7vp.service.StreamHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「智能体桥」控制接口：让 Dify 等外部智能体查询地图状态并下发视图指令。
 *
 * 完整契约见 doc/AGENT_MAP_CONTROL_SKILL.md（v1.0 + v1.1 的 N6/wait 追加 + v1.2 的 N7/N8 与批量显隐）。
 * 统一前缀 /api/projects/{projectId}/agent（N7 是例外，见下），错误体沿用 {"error": "..."}。
 *
 * <p>N7（{@code GET /api/agent/projects}）不带 projectId——它正是智能体<b>拿来问 projectId 的</b>入口，
 * 其余接口都要 projectId，所以它只能挂在 /api/agent 下，不能进 /api/projects/{projectId}。
 *
 * 两条腿：
 * <ul>
 *   <li><b>查询腿</b>（N1 targets / N2 regions）：同步返回数据。</li>
 *   <li><b>控制腿</b>（N3 下发 / N4 页面取令 / N5 回执 / N6 读回执）：后端只入队，
 *       由打开了「接入智能体」开关的 Builder 页面长轮询取令后执行。
 *       地图场景对象只活在浏览器内存里，HTTP 请求天生由浏览器发起，方向反不过来。</li>
 * </ul>
 *
 * <b>安全不变式</b>：本控制器<b>不写达梦、不改项目 application</b>，命令队列只在内存；
 * 所有指令都是运行时视图操作，刷新页面即恢复（§9）。
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    /** N3 wait=true 时等回执的默认/最大时长（毫秒） */
    private static final int DEFAULT_WAIT_MS = 5000;
    private static final int MAX_WAIT_MS = 30000;

    /** N7 列项目的默认/最大条数 */
    private static final int DEFAULT_PROJECT_LIMIT = 50;
    private static final int MAX_PROJECT_LIMIT = 200;

    /** N8 区域筛选的默认/最大目标数与默认扫描行数 */
    private static final int DEFAULT_REGION_LIMIT = 200;
    private static final int MAX_REGION_LIMIT = 2000;
    private static final int DEFAULT_SCAN_ROWS = 20000;
    private static final int MAX_SCAN_ROWS = 50000;

    /** dataset.create：行数/列数/单元格字符串长度上限（载荷走内存队列并全量往返，必须有硬顶） */
    private static final int MAX_DATASET_ROWS = 5000;
    private static final int MAX_DATASET_COLUMNS = 100;
    private static final int MAX_CELL_CHARS = 512;
    /** 列名/图层名/数据集名的长度上限 */
    private static final int MAX_NAME_CHARS = 100;
    private static final int MAX_COLUMN_NAME_CHARS = 64;
    /** 图层备注（layer.metadata.description）的字数上限，与编辑器表单一致 */
    private static final int MAX_DESCRIPTION_CHARS = 200;

    /** 数据集列类型：前端 DatasetField 的取值（见 li-sdk specs/dataset.ts） */
    private static final Set<String> COLUMN_TYPES =
            new LinkedHashSet<>(Arrays.asList("string", "number", "boolean", "geo", "date", "h3"));

    /**
     * 列类型别名 → 前端取值。中台/DB 过来的类型五花八门，而前端只认上面 6 种，
     * 这里做一层宽容映射：认不出的一律按 string（不报错——列类型只影响样式候选，不影响能不能画）。
     */
    private static final Map<String, String> COLUMN_TYPE_ALIASES = new LinkedHashMap<>();

    static {
        for (String alias : Arrays.asList("int", "integer", "long", "short", "float", "double", "decimal",
                "numeric", "bigint", "smallint", "number", "数值")) {
            COLUMN_TYPE_ALIASES.put(alias, "number");
        }
        for (String alias : Arrays.asList("bool", "boolean", "布尔")) {
            COLUMN_TYPE_ALIASES.put(alias, "boolean");
        }
        for (String alias : Arrays.asList("date", "datetime", "timestamp", "time", "日期")) {
            COLUMN_TYPE_ALIASES.put(alias, "date");
        }
        for (String alias : Arrays.asList("geo", "geometry", "point", "linestring", "polygon", "地理")) {
            COLUMN_TYPE_ALIASES.put(alias, "geo");
        }
        for (String alias : Arrays.asList("h3", "h3index")) {
            COLUMN_TYPE_ALIASES.put(alias, "h3");
        }
        for (String alias : Arrays.asList("string", "text", "varchar", "char", "str", "字符")) {
            COLUMN_TYPE_ALIASES.put(alias, "string");
        }
    }

    @Autowired
    private AgentCommandService agentCommandService;

    @Autowired
    private RegionService regionService;

    @Autowired
    private StreamHistoryService streamHistoryService;

    @Autowired
    private LayerRepository layerRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DatasetColumnRepository datasetColumnRepository;

    // ==================== N7：查项目列表（v1.2 追加） ====================

    /**
     * 列项目，按名称模糊检索。
     *
     * <p><b>为什么需要它</b>：智能体手里通常只有「项目名称」或一个模糊的说法，
     * 而除本接口外的所有接口都要求 projectId。没有这一步，智能体只能靠 {@code GET /api/projects}
     * 拿全量项目自己翻——那个接口返回的是原始实体（含体积很大的 mapConfig），且不支持按名称搜。
     * 这里只回四个轻量字段，并支持 q 模糊匹配（双向包含、大小写不敏感）。
     *
     * <p>未命中 → 200 + {@code rowCount: 0}（附录 C 决策 7：查不到不是错误）。
     *
     * GET /api/agent/projects?q=&limit=
     */
    @GetMapping("/agent/projects")
    public ResponseEntity<?> listProjects(@RequestParam(required = false) String q,
                                          @RequestParam(required = false) Integer limit) {
        try {
            int wantLimit = clampInt(limit == null ? DEFAULT_PROJECT_LIMIT : limit, 1, MAX_PROJECT_LIMIT);
            List<Project> projects = projectRepository.searchByName(q, wantLimit);

            List<Map<String, Object>> rows = new ArrayList<>();
            for (Project project : projects) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectId", project.getProjectId());
                row.put("projectName", project.getProjectName());
                row.put("description", project.getDescription());
                row.put("updateTime", project.getUpdateTime());
                rows.add(row);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("projects", rows);
            body.put("rowCount", rows.size());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("[AGENT_PROJECTS] failed q={}", q, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    // ==================== N1：查目标位置 ====================

    /**
     * 查目标位置：给 key 走精确匹配（编号），给 q 走模糊匹配（名称）。
     *
     * GET /api/projects/{projectId}/agent/targets?datasetId=&key=|q=&field=&windowMinutes=&limit=
     */
    @GetMapping("/projects/{projectId}/agent/targets")
    public ResponseEntity<?> targets(
            @PathVariable String projectId,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) String key,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String field,
            @RequestParam(required = false) Integer windowMinutes,
            @RequestParam(required = false) Integer limit) {
        try {
            StreamHistoryService.TargetResult result = streamHistoryService.queryTargets(
                    projectId, datasetId, key, q, field, windowMinutes, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("mode", result.getMode());
            body.put("field", result.getField());
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
            log.warn("[AGENT_TARGETS] failed projectId={} datasetId={} key={} q={}", projectId, datasetId, key, q, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    // ==================== N8：区域内目标（v1.2 追加） ====================

    /**
     * 区域筛选：返回该区域<b>当前</b>（各目标最新位置）落在矩形内的目标。
     *
     * <p>region 与 bbox 二选一：
     * <ul>
     *   <li>{@code region} —— 区域 key（如 bohai）或中文名/别名（如 渤海），走 N2 的同一本字典；
     *       命中的是字典里的<b>矩形</b>，所以判定精度就是矩形。</li>
     *   <li>{@code bbox} —— 直接给矩形，顺序固定 {@code minLng,minLat,maxLng,maxLat} 逗号分隔
     *       （与附录 C 决策 6 一致）。字典里没有的区域（如某个港口锚地）用这条。</li>
     * </ul>
     *
     * <p>命中多条区域名（如「海峡」）时<b>不猜</b>，返回 400 并列出候选 key，让智能体自己选。
     *
     * <p>返回的行与 N1 同形（key/lng/lat/eventTime/payload），另加 {@code region} 回填区域 key。
     * {@code countOnly=true} 时 rows 为空、rowCount 为命中目标数——用于「这片海域有多少条船」这类只需要数的问法。
     *
     * GET /api/projects/{projectId}/agent/targets/in-region?datasetId=&region=|bbox=&windowMinutes=&limit=&countOnly=&scanLimit=
     */
    @GetMapping("/projects/{projectId}/agent/targets/in-region")
    public ResponseEntity<?> targetsInRegion(
            @PathVariable String projectId,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) String region,
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) Integer windowMinutes,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "false") boolean countOnly,
            @RequestParam(required = false) Integer scanLimit) {
        try {
            boolean hasRegion = region != null && !region.trim().isEmpty();
            boolean hasBbox = bbox != null && !bbox.trim().isEmpty();
            if (hasRegion == hasBbox) {
                throw new IllegalArgumentException("region 与 bbox 必须二选一"
                        + "（region 用区域 key 或中文名，bbox 形如 117.0,37.0,122.5,41.0）");
            }

            String regionKey = null;
            String regionName = null;
            List<Double> box;
            if (hasRegion) {
                Map<String, Object> hit = resolveRegion(region.trim());
                regionKey = str(hit.get("key"));
                regionName = str(hit.get("name"));
                box = requireBbox(hit.get("bbox"));
            } else {
                box = parseBboxParam(bbox);
            }

            int wantLimit = clampInt(limit == null ? DEFAULT_REGION_LIMIT : limit, 1, MAX_REGION_LIMIT);
            int wantScan = clampInt(scanLimit == null ? DEFAULT_SCAN_ROWS : scanLimit, 1, MAX_SCAN_ROWS);

            StreamHistoryService.RegionTargetResult result = streamHistoryService.queryTargetsInRegion(
                    projectId, datasetId, regionKey, box, windowMinutes, wantLimit, countOnly, wantScan);

            Map<String, Object> filter = new LinkedHashMap<>();
            filter.put("region", hasRegion ? region.trim() : null);
            filter.put("regionKey", regionKey);
            filter.put("regionName", regionName);
            filter.put("bbox", box);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("filter", filter);
            body.put("field", result.getField());
            body.put("rows", result.getRows());
            body.put("rowCount", result.getRowCount());
            body.put("scanned", result.getScanned());
            body.put("truncated", result.isTruncated());
            return ResponseEntity.ok(body);
        } catch (StreamHistoryService.DatasetNotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(e.getMessage()));
        } catch (StreamHistoryService.TsdbUnavailable e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[AGENT_TARGETS_REGION] failed projectId={} datasetId={} region={} bbox={}",
                    projectId, datasetId, region, bbox, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /**
     * 区域入参 → 字典条目：先按 key 精确找，再按名称/别名模糊找。
     * 命中 0 条给出全部可用 key；命中多条**不猜**，列候选 key 让调用方选。
     */
    private Map<String, Object> resolveRegion(String region) {
        Map<String, Object> byKey = regionService.findByKey(region);
        if (byKey != null) {
            return byKey;
        }
        List<Map<String, Object>> hits = regionService.search(region);
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("未知区域：「" + region + "」。可用 key："
                    + String.join("、", regionService.listKeys())
                    + "（也可直接用 bbox 参数给出矩形）");
        }
        if (hits.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (Map<String, Object> hit : hits) {
                keys.add(str(hit.get("key")));
            }
            throw new IllegalArgumentException("区域名「" + region + "」命中多个区域，请改用 key：" + String.join("、", keys));
        }
        return hits.get(0);
    }

    /** bbox 查询参数是逗号分隔的字符串（对 Dify/curl 都最省事），解析成 4 个数值后走同一套校验 */
    private List<Double> parseBboxParam(String bbox) {
        String[] parts = bbox.split(",");
        if (parts.length != 4) {
            throw new IllegalArgumentException("bbox 必须是 4 个逗号分隔的数值 [minLng,minLat,maxLng,maxLat]，实际："
                    + bbox);
        }
        List<Object> raw = new ArrayList<>(4);
        for (String part : parts) {
            raw.add(part.trim());
        }
        return requireBbox(raw);
    }

    // ==================== N2：区域字典 ====================

    /**
     * 区域名 → bbox / 中心 / 缩放级。未命中返回 200 + rowCount:0（不用 404，附录 C 决策 7）。
     *
     * GET /api/projects/{projectId}/agent/regions?q=
     */
    @GetMapping("/projects/{projectId}/agent/regions")
    public ResponseEntity<?> regions(@PathVariable String projectId,
                                     @RequestParam(required = false) String q) {
        List<Map<String, Object>> hit = regionService.search(q);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("regions", hit);
        body.put("rowCount", hit.size());
        return ResponseEntity.ok(body);
    }

    // ==================== N3：下发指令 ====================

    /**
     * 下发一条地图指令。
     *
     * <p>POST /api/projects/{projectId}/agent/commands?wait=&timeoutMs=
     * <br>body: {"type":"layer.visibility","payload":{...}}
     *
     * <p>{@code wait=true}（v1.1 追加，可选）会等页面的执行回执，响应里多一个 {@code result} 字段——
     * 智能体一次调用就能确认「页面真的执行了」，而不是发完就走、命中「页面根本没开」的假成功。
     */
    @PostMapping("/projects/{projectId}/agent/commands")
    public ResponseEntity<?> enqueue(@PathVariable String projectId,
                                     @RequestParam(defaultValue = "false") boolean wait,
                                     @RequestParam(required = false) Integer timeoutMs,
                                     @RequestBody(required = false) Map<String, Object> body) {
        String type = null;
        try {
            if (body == null) {
                throw new IllegalArgumentException("缺少请求体（type + payload）");
            }
            type = str(body.get("type"));
            Map<String, Object> payload = asMap(body.get("payload"));
            Map<String, Object> normalized = normalizeCommand(projectId, type, payload);

            AgentCommandService.EnqueueResult enqueued =
                    agentCommandService.enqueue(projectId, type, normalized);
            AgentCommandService.Command cmd = enqueued.getCommand();

            AgentCommandService.CommandResult result = null;
            if (wait) {
                int ms = timeoutMs == null ? DEFAULT_WAIT_MS : Math.max(0, Math.min(timeoutMs, MAX_WAIT_MS));
                result = agentCommandService.awaitResult(projectId, cmd.getCmdId(), ms);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cmdId", cmd.getCmdId());
            out.put("seq", cmd.getSeq());
            out.put("status", result == null ? "queued" : "done");
            // 哪个页面能执行它——config 类指令只有 Builder 页的执行器会取（v1.4）：
            // 只开着地图预览页时命令会一直积压为 queued，回执里带上一句省得智能体干等
            out.put("capability", AgentCommandService.capabilityOf(type));
            if (result == null && wait) {
                out.put("hint", hintFor(AgentCommandService.capabilityOf(type)));
            }
            if (wait) {
                out.put("result", result == null ? null : resultBody(result));
            }
            return ResponseEntity.ok(out);
        } catch (AgentCommandService.RateLimited e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(errorBody(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(errorBody(e.getMessage()));
        } catch (Exception e) {
            log.warn("[AGENT_COMMAND] enqueue failed projectId={} type={}", projectId, type, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    /**
     * 命令超时未执行时给智能体的一句人话解释（哪个页面没开、开关没开）。
     *
     * <p>注意 {@code config} 指的那两个条件是<b>并列</b>的：指令要写进项目配置，所以就**必须**在 Builder 页
     * （只有它有编辑器状态与自动保存），又因为授权开关只有地图侧「智能体桥」组件上有，
     * 用户得先在**同一个页面**上把那个开关打开。别写成「打开智能体配置桥开关」——
     * 「智能体配置桥」是执行器组件，它**没有**自己的开关。
     */
    private static String hintFor(String capability) {
        if (AgentCommandService.CAPABILITY_CONFIG.equals(capability)) {
            return "该指令会写入项目配置，需在 Builder 页（/builder/{projectId}）执行，"
                    + "并先在该页地图的「智能体桥」组件上打开「接入智能体」开关（该开关同时授权配置写入）";
        }
        return "该指令只改运行时视图，需在地图页打开「智能体桥」组件上的「接入智能体」开关后才会执行（刷新页面即恢复）";
    }

    // ==================== N4：页面取令（长轮询） ====================

    /**
     * 页面取令：返回严格大于 since 的指令；无新指令时挂起至多 timeout 秒（服务端上限 30s）。
     * 省略 since → 不回放任何指令、只返回当前最新 seq（页面首次连接对齐游标用）。
     *
     * <p>{@code capabilities}（v1.4 追加，可选）：逗号分隔的能力分组，页面侧有两个执行器各取各的——
     * {@code runtime}（地图预览页的「智能体桥」：只改运行时视图）与
     * {@code config}（Builder 页的「智能体配置桥」：写项目配置）。省略 = 不过滤，返回全部（向后兼容）。
     * 不属于本能力的指令<b>留在队列里</b>由另一侧取，不会被取走却执行不了。
     *
     * GET /api/projects/{projectId}/agent/commands?since=&timeout=&capabilities=runtime|config
     */
    @GetMapping("/projects/{projectId}/agent/commands")
    public ResponseEntity<?> poll(@PathVariable String projectId,
                                  @RequestParam(required = false) Long since,
                                  @RequestParam(defaultValue = "25") long timeout,
                                  @RequestParam(required = false) String capabilities) {
        try {
            Set<String> capabilityFilter = parseCapabilities(capabilities);
            AgentCommandService.PollResult polled =
                    agentCommandService.poll(projectId, since, timeout * 1000L, capabilityFilter);
            List<Map<String, Object>> commands = new ArrayList<>();
            for (AgentCommandService.Command cmd : polled.getCommands()) {
                commands.add(commandBody(cmd));
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("commands", commands);
            body.put("seq", polled.getSeq());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            log.warn("[AGENT_POLL] failed projectId={} since={}", projectId, since, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    // ==================== N5：页面回执 ====================

    /**
     * 页面上报执行结果；重复提交幂等覆盖，未知 cmdId → 404。
     *
     * POST /api/projects/{projectId}/agent/commands/{cmdId}/result
     */
    @PostMapping("/projects/{projectId}/agent/commands/{cmdId}/result")
    public ResponseEntity<?> reportResult(@PathVariable String projectId,
                                          @PathVariable String cmdId,
                                          @RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> source = body == null ? new LinkedHashMap<>() : body;
            boolean ok = Boolean.TRUE.equals(source.get("ok"));
            String error = str(source.get("error"));
            Map<String, Object> detail = asMap(source.get("detail"));

            if (!agentCommandService.reportResult(projectId, cmdId, ok, error, detail)) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("未知指令: " + cmdId));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cmdId", cmdId);
            out.put("ok", true);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("[AGENT_RESULT] report failed projectId={} cmdId={}", projectId, cmdId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorBody(e.getMessage()));
        }
    }

    // ==================== N6：读回执（v1.1 追加） ====================

    /**
     * 读某条指令及其执行回执。
     *
     * <p><b>为什么追加这个接口</b>：v1.0 只有 N5（页面写回执）没有读回执的路径，
     * 而附录 B 第 5 条要求智能体「有回执时读回执确认」，功能 02 的第 4 步也依赖读回执——
     * 缺了读路径，这两个要求都落不了地。属纯新增，不影响既有调用方（附录 D：只加不删）。
     *
     * <p>未执行 → {@code status: "queued"}；cmdId 未知（已被挤出）→ 404。
     *
     * GET /api/projects/{projectId}/agent/commands/{cmdId}/result
     */
    @GetMapping("/projects/{projectId}/agent/commands/{cmdId}/result")
    public ResponseEntity<?> getResult(@PathVariable String projectId,
                                       @PathVariable String cmdId) {
        AgentCommandService.Command cmd = agentCommandService.find(projectId, cmdId);
        if (cmd == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("未知指令: " + cmdId));
        }
        AgentCommandService.CommandResult result = cmd.getResult();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cmdId", cmd.getCmdId());
        out.put("seq", cmd.getSeq());
        out.put("type", cmd.getType());
        out.put("status", result == null ? "queued" : "done");
        out.put("result", result == null ? null : resultBody(result));
        return ResponseEntity.ok(out);
    }

    // ==================== 指令校验与归一化 ====================

    /**
     * 白名单校验 + 逐字段校验 + 归一化（§9.4）。
     *
     * <p>归一化的两处关键点：
     * <ul>
     *   <li>{@code region} 模式在此处就解析成 {@code bounds} 后入队——页面执行器只认 point/bounds（附录 C 决策 16）。</li>
     *   <li>{@code layerName} 解析成 {@code layerId}，并在本项目内做唯一匹配（附录 C 决策 15/17）。</li>
     * </ul>
     */
    private Map<String, Object> normalizeCommand(String projectId, String type, Map<String, Object> payload) {
        if (type == null || type.isEmpty()) {
            throw new IllegalArgumentException("缺少指令类型 type");
        }
        switch (type) {
            case "layer.visibility":
                return normalizeVisibility(projectId, payload);
            case "layer.isolate":
                return normalizeIsolate(projectId, payload);
            case "layer.bringToFront":
            case "layer.sendToBack":
                // 层级只提供「提到最上层 / 压到最下层」两个动作（v1.3，see resolveLayerRef）；
                // zIndex 的具体取值由页面侧算——层清单与瓦片层只有页面知道
                return resolveLayerRef(projectId, payload, type);
            case "map.focus":
                return normalizeFocus(payload);
            case "map.reset":
                return new LinkedHashMap<>();
            case "target.select":
                return normalizeTargetSelect(payload);
            case "dataset.create":
                return normalizeDatasetCreate(projectId, payload);
            case "layer.update":
                return normalizeLayerUpdate(projectId, payload);
            default:
                throw new IllegalArgumentException("未知指令类型：" + type
                        + "（支持 layer.visibility / layer.isolate / layer.bringToFront / layer.sendToBack"
                        + " / map.focus / map.reset / target.select / dataset.create / layer.update）");
        }
    }

    // ==================== dataset.create / layer.update（v1.4 追加） ====================

    /**
     * 建数据集（+ 默认自动生成图层）：<b>两步建图层的「第一步」</b>（附录 C 决策 31）。
     *
     * <p>为什么以「数据集」为第一参数：数据从哪来这件事，产品里本来就是数据集在表达
     * （示例数据/文件上传/中台表/流式都是建数据集），而且编辑器<b>已有</b>「新增数据集 → 自动生成图层」
     * 的完整实现（数据集带 {@code metadata._autoCreateLayers} → {@code editor-service} 按列名猜可视化类型、
     * 建图层、绑 LayerPopup、把地图飞到数据上）。所以这里只要把数据集交出去，图层是产品自己长出来的，
     * 不需要智能体描述 visConfig 全貌，也不需要我们复制一套「猜类型」逻辑。
     *
     * <p>只支持<b>内联数据</b>（columns + rows）：智能体直接给数据，无外部依赖、最不容易出错。
     * 中台表/流式以后要加，是往 {@code source} 里加分支，契约形态不变。
     *
     * <p>载荷校验从严（这里拒绝，好过页面执行到一半失败）：行数/列数/单元格长度都有硬顶，
     * 行里的键必须都在 columns 里、值必须是标量（嵌套对象请改用 lng/lat 两列 + parser）。
     */
    private Map<String, Object> normalizeDatasetCreate(String projectId, Map<String, Object> payload) {
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new IllegalArgumentException("项目不存在：" + projectId);
        }

        String datasetName = str(payload.get("datasetName"));
        if (datasetName == null) {
            throw new IllegalArgumentException("dataset.create 需要 datasetName");
        }
        if (datasetName.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("datasetName 过长（≤" + MAX_NAME_CHARS + " 字符）：" + datasetName.length());
        }

        List<Map<String, Object>> columns = normalizeColumns(payload.get("columns"));
        List<String> columnNames = new ArrayList<>();
        for (Map<String, Object> column : columns) {
            columnNames.add((String) column.get("name"));
        }
        List<Map<String, Object>> rows = normalizeRows(payload.get("rows"), columnNames);

        String layerName = str(payload.get("layerName"));
        if (layerName != null && layerName.length() > MAX_NAME_CHARS) {
            throw new IllegalArgumentException("layerName 过长（≤" + MAX_NAME_CHARS + " 字符）");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetName", datasetName);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("layerName", layerName);
        out.put("autoCreateLayer", asBoolean(payload.get("autoCreateLayer"), true));
        return out;
    }

    /** 列定义：[{name, type?, displayName?}]；列名去重（重名无法在行对象里区分），类型走别名映射 */
    private List<Map<String, Object>> normalizeColumns(Object raw) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new IllegalArgumentException("dataset.create 需要 columns（至少一列，形如 [{name:'船名',type:'string'}]）");
        }
        List<?> list = (List<?>) raw;
        if (list.size() > MAX_DATASET_COLUMNS) {
            throw new IllegalArgumentException("列数过多（≤" + MAX_DATASET_COLUMNS + "）：" + list.size());
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("columns 的元素必须是对象 [{name, type}]");
            }
            Map<?, ?> column = (Map<?, ?>) item;
            String name = str(column.get("name"));
            if (name == null) {
                throw new IllegalArgumentException("columns 的元素缺少 name");
            }
            if (name.length() > MAX_COLUMN_NAME_CHARS) {
                throw new IllegalArgumentException("列名过长（≤" + MAX_COLUMN_NAME_CHARS + " 字符）：" + name);
            }
            if (hasControlChar(name)) {
                throw new IllegalArgumentException("列名含控制字符：" + name);
            }
            if (seen.put(name, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("列名重复：" + name);
            }

            String type = mapColumnType(str(column.get("type")));
            String displayName = str(column.get("displayName"));
            if (displayName != null && displayName.length() > MAX_COLUMN_NAME_CHARS) {
                throw new IllegalArgumentException("displayName 过长（≤" + MAX_COLUMN_NAME_CHARS + " 字符）：" + displayName);
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", name);
            out.put("type", type);
            out.put("displayName", displayName);
            columns.add(out);
        }
        return columns;
    }

    /** 行：[{列名: 标量}]；键必须都在 columns 里（不静默丢数据），值必须是标量（嵌套对象交代不清楚） */
    private List<Map<String, Object>> normalizeRows(Object raw, List<String> columnNames) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new IllegalArgumentException("dataset.create 需要 rows（至少一行，形如 [{lng:120.1,lat:36.2}]）");
        }
        List<?> list = (List<?>) raw;
        if (list.size() > MAX_DATASET_ROWS) {
            throw new IllegalArgumentException("行数过多（≤" + MAX_DATASET_ROWS + "）：" + list.size()
                    + "；大批量数据请改用文件上传或中台数据表");
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map)) {
                throw new IllegalArgumentException("rows 第 " + (i + 1) + " 行不是对象");
            }
            Map<?, ?> row = (Map<?, ?>) item;
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : row.entrySet()) {
                String key = entry.getKey() == null ? null : entry.getKey().toString();
                if (key == null || !columnNames.contains(key)) {
                    throw new IllegalArgumentException("rows 第 " + (i + 1) + " 行有列定义里没有的键：「" + key
                            + "」。可用列：" + String.join("、", columnNames));
                }
                Object value = entry.getValue();
                if (value instanceof Map || value instanceof List) {
                    throw new IllegalArgumentException("rows 第 " + (i + 1) + " 行的「" + key
                            + "」是嵌套结构，只接受标量（字符串/数值/布尔/空）；坐标请拆成 lng/lat 两列");
                }
                if (value instanceof String && ((String) value).length() > MAX_CELL_CHARS) {
                    throw new IllegalArgumentException("rows 第 " + (i + 1) + " 行的「" + key
                            + "」过长（≤" + MAX_CELL_CHARS + " 字符）");
                }
                out.put(key, value);
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("rows 第 " + (i + 1) + " 行是空对象");
            }
            rows.add(out);
        }
        return rows;
    }

    /**
     * 改图层属性（两步建图层的「第二步」）：受控白名单，不接受自由 JSON。
     *
     * <p>为什么不让智能体直接写 visConfig：各图层资产的样式字段名完全不同
     * （BubbleLayer 是 fillColor/radius、LineLayer 是 color/size、IconLayer 是 iconImg…），
     * 放开写等于把「资产内部结构」变成契约的一部分，改一个资产就破坏契约。
     * 这里只暴露跨资产同义的少量参数，由页面按图层类型映射到该资产的主字段
     * （映射表在页面的执行器里，未覆盖的资产明确报错而不是瞎写）。
     *
     * <p>{@code parser} 的列名在<b>后端</b>就校验（能查到数据集列定义），语义错误尽早以 400 返回；
     * {@code visType} 是否为已注册资产只能由页面校验（后端不认识资产包）。
     */
    private Map<String, Object> normalizeLayerUpdate(String projectId, Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>(resolveLayerRef(projectId, payload, "layer.update"));
        String layerId = (String) out.get("layerId");
        // 丢掉 resolveLayerRef 回填的 layerName（那是「匹配到的旧名」）：执行侧按 layerId 定位，
        // 改名走下面的 name。两个键并存会让「到底改不改名」变得看运气。
        out.remove("layerName");
        boolean touched = false;

        if (payload.containsKey("name")) {
            String name = str(payload.get("name"));
            if (name == null) {
                throw new IllegalArgumentException("layer.update 的 name 不能为空（要清空请改 description）");
            }
            if (name.length() > MAX_NAME_CHARS) {
                throw new IllegalArgumentException("图层名过长（≤" + MAX_NAME_CHARS + " 字符）");
            }
            out.put("name", name);
            touched = true;
        }

        if (payload.containsKey("description")) {
            // 空字符串 = 清空（图层备注是可选项，允许清掉）
            String description = payload.get("description") == null ? "" : payload.get("description").toString().trim();
            if (description.length() > MAX_DESCRIPTION_CHARS) {
                throw new IllegalArgumentException("图层备注过长（≤" + MAX_DESCRIPTION_CHARS + " 字）");
            }
            out.put("description", description);
            touched = true;
        }

        if (payload.containsKey("visible")) {
            Object visible = payload.get("visible");
            if (!(visible instanceof Boolean)) {
                throw new IllegalArgumentException("layer.update 的 visible 必须是布尔值");
            }
            out.put("visible", visible);
            touched = true;
        }

        if (payload.containsKey("parser")) {
            out.put("parser", normalizeParser(projectId, layerId, payload.get("parser")));
            touched = true;
        }

        if (payload.containsKey("color")) {
            String color = str(payload.get("color"));
            if (color == null || color.length() > 32) {
                throw new IllegalArgumentException("color 必须是颜色字符串（如 #F86624 / rgb(90,216,166)）");
            }
            out.put("color", color);
            touched = true;
        }

        if (payload.containsKey("size")) {
            Double size = num(payload.get("size"));
            if (size == null || size < 0 || size > 100000) {
                throw new IllegalArgumentException("size 必须是 [0, 100000] 内的数值");
            }
            out.put("size", size);
            touched = true;
        }

        if (payload.containsKey("opacity")) {
            Double opacity = num(payload.get("opacity"));
            if (opacity == null || opacity < 0 || opacity > 1) {
                throw new IllegalArgumentException("opacity 必须是 [0, 1] 内的数值");
            }
            out.put("opacity", opacity);
            touched = true;
        }

        if (payload.containsKey("visType")) {
            String visType = str(payload.get("visType"));
            if (visType == null || visType.length() > MAX_COLUMN_NAME_CHARS) {
                throw new IllegalArgumentException("visType 必须是图层资产名（如 BubbleLayer / LineLayer / IconLayer）");
            }
            out.put("visType", visType);
            touched = true;
        }

        if (!touched) {
            throw new IllegalArgumentException("layer.update 至少要给一个要改的字段"
                    + "（name / description / visible / parser / color / size / opacity / visType）");
        }
        return out;
    }

    /**
     * 坐标字段映射：{@code {x,y}} 或 {@code {geometry}}，二者不可混用。
     * 列名必须在该图层绑定的数据集里真实存在——查得到就当场报错并回全部可用列，
     * 省掉「下发了才在页面上失败」的一轮往返。
     */
    private Map<String, Object> normalizeParser(String projectId, String layerId, Object raw) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("parser 必须是对象：{x,y} 或 {geometry}");
        }
        Map<?, ?> parser = (Map<?, ?>) raw;
        String x = str(parser.get("x"));
        String y = str(parser.get("y"));
        String geometry = str(parser.get("geometry"));

        Map<String, Object> out = new LinkedHashMap<>();
        if (geometry != null) {
            if (x != null || y != null) {
                throw new IllegalArgumentException("parser 的 geometry 与 x/y 不能同时给");
            }
            out.put("geometry", geometry);
        } else if (x != null && y != null) {
            out.put("x", x);
            out.put("y", y);
        } else {
            throw new IllegalArgumentException("parser 需要 {x,y} 或 {geometry}（x/y 必须成对给）");
        }

        List<String> available = listDatasetColumns(projectId, layerId);
        if (!available.isEmpty()) {
            for (Object value : out.values()) {
                String field = String.valueOf(value);
                if (!available.contains(field)) {
                    throw new IllegalArgumentException("parser 的字段「" + field + "」不在该图层的数据集里。可用列："
                            + String.join("、", available));
                }
            }
        }
        return out;
    }

    /** 该图层绑定的数据集的列名（查不到就返回空列表 = 跳过字段存在性校验） */
    private List<String> listDatasetColumns(String projectId, String layerId) {
        Layer layer = layerRepository.findById(layerId);
        if (layer == null || layer.getDatasetId() == null) {
            return new ArrayList<>();
        }
        List<DatasetColumn> columns = datasetColumnRepository.findByDatasetId(layer.getDatasetId());
        List<String> names = new ArrayList<>();
        for (DatasetColumn column : columns) {
            if (column.getColumnName() != null) {
                names.add(column.getColumnName());
            }
        }
        return names;
    }

    /** 列类型别名映射：认不出的一律 string（列类型只影响样式候选，不影响能不能画） */
    private static String mapColumnType(String raw) {
        if (raw == null) {
            return "string";
        }
        String key = raw.toLowerCase();
        if (COLUMN_TYPES.contains(key)) {
            return key;
        }
        String mapped = COLUMN_TYPE_ALIASES.get(key);
        return mapped == null ? "string" : mapped;
    }

    private static boolean hasControlChar(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** capabilities=runtime,config → Set；非法值报 400 并列出全部合法值（省略返回 null = 不过滤） */
    private static Set<String> parseCapabilities(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            if (!AgentCommandService.allCapabilities().contains(item)) {
                throw new IllegalArgumentException("未知的能力分组：「" + item + "」。可用："
                        + String.join("、", AgentCommandService.allCapabilities()));
            }
            out.add(item);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * 图层显隐，两种形态（单层是 v1.0 原样，批量是 v1.2 追加）：
     * <ul>
     *   <li>单层：{@code {layerId|layerName, visible}} —— 不做任何「保留瓦片」的特殊处理，
     *       显式指名某个图层就照办（包括显式隐藏某个瓦片图层）。</li>
     *   <li>批量：{@code {scope:'all', visible, keepTiles?}} —— 整片图层一起改。
     *       {@code keepTiles} 默认 true，表示<b>瓦片图层一律不动</b>，这样「隐藏全部图层」
     *       不会把底图也关掉、剩下空白画布；要连瓦片一起改才需要显式传 false。</li>
     * </ul>
     * 瓦片判定在页面侧做（图层资产类型或它绑定的数据集类型），后端不复制一份图层类型知识。
     */
    private Map<String, Object> normalizeVisibility(String projectId, Map<String, Object> payload) {
        Object visible = payload.get("visible");
        if (!(visible instanceof Boolean)) {
            throw new IllegalArgumentException("layer.visibility 需要布尔字段 visible");
        }
        String layerId = str(payload.get("layerId"));
        String layerName = str(payload.get("layerName"));

        Map<String, Object> out = new LinkedHashMap<>();

        String scope = str(payload.get("scope"));
        if (scope != null) {
            if (!"all".equals(scope)) {
                throw new IllegalArgumentException("未知的 layer.visibility scope：「" + scope + "」（目前仅支持 all）");
            }
            if (layerId != null || layerName != null) {
                throw new IllegalArgumentException("layer.visibility 的 scope 与 layerId/layerName 不能同时给");
            }
            out.put("scope", "all");
            out.put("visible", visible);
            out.put("keepTiles", asBoolean(payload.get("keepTiles"), true));
            return out;
        }

        out.putAll(resolveLayerRef(projectId, payload, "layer.visibility"));
        out.put("visible", visible);
        return out;
    }

    /**
     * 单个图层引用解析：{@code {layerId 或 layerName}} → {@code {layerId, layerName}}。
     *
     * <p>三条规矩（与图层显隐同规，附录 C 决策 15/17）：
     * <ul>
     *   <li>不存在与不属于本项目合并成同一条错误——不泄露其它项目的图层存在性；</li>
     *   <li>名字查不到时把本项目全部图层名回给调用方，方便纠正；</li>
     *   <li>一个名字命中多个图层时<b>不猜</b>，400 并列出候选 id。</li>
     * </ul>
     *
     * <p>{@code commandName} 只用于错误文案，让「哪条指令参数不全」一目了然。
     */
    private Map<String, Object> resolveLayerRef(String projectId, Map<String, Object> payload, String commandName) {
        String layerId = str(payload.get("layerId"));
        String layerName = str(payload.get("layerName"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (layerId != null) {
            Layer layer = layerRepository.findById(layerId);
            if (layer == null || !projectId.equals(layer.getProjectId())) {
                throw new IllegalArgumentException("图层不存在或不属于该项目：" + layerId);
            }
            out.put("layerId", layer.getLayerId());
            out.put("layerName", layer.getLayerName());
            return out;
        }
        if (layerName == null) {
            throw new IllegalArgumentException(commandName + " 需要 layerId 或 layerName");
        }

        List<Layer> layers = layerRepository.findByProjectId(projectId);
        List<Layer> hit = new ArrayList<>();
        List<String> allNames = new ArrayList<>();
        for (Layer layer : layers) {
            String name = layer.getLayerName() == null ? "" : layer.getLayerName();
            allNames.add(name);
            if (name.equals(layerName)) {
                hit.add(layer);
            }
        }
        if (hit.isEmpty()) {
            throw new IllegalArgumentException("未找到名为「" + layerName + "」的图层。本项目图层："
                    + String.join("、", allNames));
        }
        if (hit.size() > 1) {
            List<String> ids = new ArrayList<>();
            for (Layer layer : hit) {
                ids.add(layer.getLayerId());
            }
            throw new IllegalArgumentException("图层名「" + layerName + "」在本项目有 " + hit.size()
                    + " 个同名图层，请改用 layerId：" + String.join("、", ids));
        }
        out.put("layerId", hit.get(0).getLayerId());
        out.put("layerName", hit.get(0).getLayerName());
        return out;
    }

    /**
     * 图层隔离（v1.2 追加）：只显示点名的图层，其余全部隐藏。
     *
     * <p>为什么单独一条指令而不复用 layer.visibility：智能体要表达「只看 A 和 B」时，
     * 用单层形态得先把项目里所有图层列一遍、再逐条下发隐藏——既啰嗦又会在两次调用之间留下中间态
     * （用户会看到图层闪一下）。这里一次调用原子完成。
     *
     * <p>{@code keepTiles} 默认 true，语义与批量显隐一致：瓦片图层一律不动、
     * 且不计入 isolate（否则「只看某个业务图层」会把底图也关掉）。
     *
     * <p>layerIds 与 layerNames 可以混用，按 id 去重；重名不猜（与 layer.visibility 同规，附录 C 决策 15）。
     */
    private Map<String, Object> normalizeIsolate(String projectId, Map<String, Object> payload) {
        Object rawIds = payload.get("layerIds");
        Object rawNames = payload.get("layerNames");
        List<?> ids = rawIds instanceof List ? (List<?>) rawIds : new ArrayList<>();
        List<?> names = rawNames instanceof List ? (List<?>) rawNames : new ArrayList<>();
        if (ids.isEmpty() && names.isEmpty()) {
            throw new IllegalArgumentException("layer.isolate 需要 layerIds 或 layerNames（至少给一个）");
        }

        List<Layer> layers = layerRepository.findByProjectId(projectId);
        List<String> allNames = new ArrayList<>();
        for (Layer layer : layers) {
            allNames.add(layer.getLayerName() == null ? "" : layer.getLayerName());
        }

        // LinkedHashMap 保序去重：同一个图层被 id 和 name 各点一次也只留一份
        Map<String, Layer> picked = new LinkedHashMap<>();
        for (Object raw : ids) {
            String layerId = str(raw);
            if (layerId == null) {
                continue;
            }
            Layer hit = findLayerById(layers, layerId);
            if (hit == null) {
                throw new IllegalArgumentException("图层不存在或不属于该项目：" + layerId);
            }
            picked.put(hit.getLayerId(), hit);
        }
        for (Object raw : names) {
            String layerName = str(raw);
            if (layerName == null) {
                continue;
            }
            List<Layer> hit = new ArrayList<>();
            for (Layer layer : layers) {
                String name = layer.getLayerName() == null ? "" : layer.getLayerName();
                if (name.equals(layerName)) {
                    hit.add(layer);
                }
            }
            if (hit.isEmpty()) {
                throw new IllegalArgumentException("未找到名为「" + layerName + "」的图层。本项目图层："
                        + String.join("、", allNames));
            }
            if (hit.size() > 1) {
                List<String> candidates = new ArrayList<>();
                for (Layer layer : hit) {
                    candidates.add(layer.getLayerId());
                }
                throw new IllegalArgumentException("图层名「" + layerName + "」在本项目有 " + hit.size()
                        + " 个同名图层，请改用 layerIds：" + String.join("、", candidates));
            }
            picked.put(hit.get(0).getLayerId(), hit.get(0));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Layer layer : picked.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("layerId", layer.getLayerId());
            item.put("layerName", layer.getLayerName());
            out.add(item);
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("layers", out);
        normalized.put("keepTiles", asBoolean(payload.get("keepTiles"), true));
        return normalized;
    }

    private static Layer findLayerById(List<Layer> layers, String layerId) {
        for (Layer layer : layers) {
            if (layerId.equals(layer.getLayerId())) {
                return layer;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeFocus(Map<String, Object> payload) {
        String mode = str(payload.get("mode"));
        if (mode == null) {
            throw new IllegalArgumentException("map.focus 需要 mode（point / bounds / region）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        switch (mode) {
            case "point": {
                Double lng = requireLng(payload.get("lng"));
                Double lat = requireLat(payload.get("lat"));
                Double zoom = num(payload.get("zoom"));
                out.put("mode", "point");
                out.put("lng", lng);
                out.put("lat", lat);
                out.put("zoom", zoom == null ? 12 : clamp(zoom, 0d, 22d));
                break;
            }
            case "bounds": {
                List<Double> bbox = requireBbox(payload.get("bbox"));
                out.put("mode", "bounds");
                out.put("bbox", bbox);
                Double padding = num(payload.get("padding"));
                if (padding != null) {
                    out.put("padding", clamp(padding, 0d, 500d));
                }
                break;
            }
            case "region": {
                String regionKey = str(payload.get("regionKey"));
                if (regionKey == null) {
                    throw new IllegalArgumentException("region 模式需要 regionKey");
                }
                Map<String, Object> region = regionService.findByKey(regionKey);
                if (region == null) {
                    // 查不到就由智能体用自己的地理常识给 bbox 走 bounds 模式（§五 功能 05 的 ③b）
                    throw new IllegalArgumentException("未知区域：「" + regionKey + "」。可用 key："
                            + String.join("、", regionService.listKeys())
                            + "（也可直接用 bounds 模式给出 bbox）");
                }
                out.putAll(regionService.toBoundsPayload(region));
                Double padding = num(payload.get("padding"));
                if (padding != null) {
                    out.put("padding", clamp(padding, 0d, 500d));
                }
                break;
            }
            default:
                throw new IllegalArgumentException("未知的 map.focus mode：" + mode + "（支持 point / bounds / region）");
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeTargetSelect(Map<String, Object> payload) {
        Object rawTarget = payload.get("target");
        if (!(rawTarget instanceof Map)) {
            throw new IllegalArgumentException("target.select 需要 target 对象");
        }
        Map<String, Object> target = (Map<String, Object>) rawTarget;
        Double lng = requireLng(target.get("lng"));
        Double lat = requireLat(target.get("lat"));

        Map<String, Object> normalizedTarget = new LinkedHashMap<>();
        normalizedTarget.put("key", str(target.get("key")));
        normalizedTarget.put("name", str(target.get("name")));
        normalizedTarget.put("lng", lng);
        normalizedTarget.put("lat", lat);
        normalizedTarget.put("payload", asMap(target.get("payload")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", normalizedTarget);
        out.put("focus", asBoolean(payload.get("focus"), true));
        out.put("openPanel", asBoolean(payload.get("openPanel"), true));
        return out;
    }

    // ==================== 小工具 ====================

    /** 经纬度必须有限且在合法范围内，否则页面聚焦会飞到一个非法坐标 */
    private Double requireLng(Object v) {
        Double lng = num(v);
        if (lng == null || lng.isNaN() || lng.isInfinite() || lng < -180d || lng > 180d) {
            throw new IllegalArgumentException("经度 lng 必须是 [-180, 180] 内的数值");
        }
        return lng;
    }

    private Double requireLat(Object v) {
        Double lat = num(v);
        if (lat == null || lat.isNaN() || lat.isInfinite() || lat < -90d || lat > 90d) {
            throw new IllegalArgumentException("纬度 lat 必须是 [-90, 90] 内的数值");
        }
        return lat;
    }

    /** bbox 顺序固定 [minLng, minLat, maxLng, maxLat]（附录 C 决策 6） */
    private List<Double> requireBbox(Object v) {
        if (!(v instanceof List) || ((List<?>) v).size() != 4) {
            throw new IllegalArgumentException("bbox 必须是 4 个元素的数组 [minLng, minLat, maxLng, maxLat]");
        }
        List<?> raw = (List<?>) v;
        List<Double> bbox = new ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            Double d = num(raw.get(i));
            if (d == null) {
                throw new IllegalArgumentException("bbox 第 " + (i + 1) + " 个元素不是数值");
            }
            bbox.add(d);
        }
        if (bbox.get(0) >= bbox.get(2) || bbox.get(1) >= bbox.get(3)) {
            throw new IllegalArgumentException("bbox 必须满足 minLng < maxLng 且 minLat < maxLat：" + bbox);
        }
        if (bbox.get(0) < -180d || bbox.get(2) > 180d || bbox.get(1) < -90d || bbox.get(3) > 90d) {
            throw new IllegalArgumentException("bbox 超出经纬度范围：" + bbox);
        }
        return bbox;
    }

    private Map<String, Object> commandBody(AgentCommandService.Command cmd) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cmdId", cmd.getCmdId());
        out.put("seq", cmd.getSeq());
        out.put("type", cmd.getType());
        out.put("payload", cmd.getPayload());
        return out;
    }

    private Map<String, Object> resultBody(AgentCommandService.CommandResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", r.isOk());
        out.put("error", r.getError());
        out.put("detail", r.getDetail());
        out.put("executedAt", r.getExecutedAt());
        return out;
    }

    private Map<String, Object> errorBody(String msg) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("error", msg);
        return err;
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Double num(Object v) {
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        if (v instanceof String) {
            try {
                return Double.parseDouble(((String) v).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean asBoolean(Object v, boolean fallback) {
        return v instanceof Boolean ? (Boolean) v : fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
