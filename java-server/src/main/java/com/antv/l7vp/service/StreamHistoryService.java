package com.antv.l7vp.service;

import com.antv.l7vp.config.FlinkJobProperties;
import com.antv.l7vp.model.Dataset;
import com.antv.l7vp.repository.DatasetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 流式数据集「历史轨迹」查询：从时序库（TimescaleDB/PostgreSQL）取某个目标的历史报文点。
 * 另含「目标位置查询」（queryTargets，智能体技能 N1），与轨迹查询共用连接与白名单校验。
 *
 * 时序表结构由 Flink 作业自建（见 flink-job 的 StreamDatasetJob.TimescaleDbSink）：
 * <pre>
 *   &lt;table&gt;(dataset_id text NOT NULL, event_time timestamptz NOT NULL, payload jsonb NOT NULL)
 * </pre>
 * 目标标识不是独立列，而是 payload 里的一个键（键名 = 数据集 metadata.streamKey，如船=mmsi）。
 *
 * 连接信息复用 flink.tsdb.*（与作业写入同一套配置）；这里只要求 url 非空，
 * flink.tsdb.enabled 仅控制「作业是否写入」，不影响读取。
 *
 * 只读路径，低频调用，沿用 DbConnectionService 的一次查询一条物理连接风格，不引入连接池。
 */
@Service
public class StreamHistoryService {

    private static final Logger log = LoggerFactory.getLogger(StreamHistoryService.class);

    /** 表名与 payload 键名都来自配置/前端表单，拼进 SQL 前必须过白名单 */
    private static final Pattern SAFE_IDENT = Pattern.compile("^[a-zA-Z0-9_]+$");

    /** 单次查询上限的硬顶（即便 historyMaxRows 配得再大也不超过这个数） */
    private static final int ABSOLUTE_MAX_ROWS = 50000;

    /** 区域筛选（N8）默认扫描的原始报文行数上限 */
    private static final int DEFAULT_SCAN_ROWS = 20000;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private FlinkJobProperties flinkJobProperties;

    @Autowired
    private ObjectMapper objectMapper;

    /** 数据集不存在或不属于该项目 */
    public static class DatasetNotFound extends RuntimeException {
        public DatasetNotFound(String msg) {
            super(msg);
        }
    }

    /** 未配置时序库地址（读路径不可用） */
    public static class TsdbUnavailable extends RuntimeException {
        public TsdbUnavailable(String msg) {
            super(msg);
        }
    }

    /** 目标位置查询结果（N1，见 doc/AGENT_MAP_CONTROL_SKILL.md §4） */
    public static class TargetResult {
        private String mode;
        private String field;
        private List<Map<String, Object>> rows = new ArrayList<>();
        private boolean truncated;

        public String getMode() {
            return mode;
        }

        public String getField() {
            return field;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public int getRowCount() {
            return rows.size();
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    /** 区域内目标查询结果（N8，v1.2 追加） */
    public static class RegionTargetResult {
        private String field;
        private String regionKey;
        private List<Map<String, Object>> rows = new ArrayList<>();
        /** rows 的条数；countOnly=true 时为命中的目标数（rows 为空） */
        private int rowCount;
        /** 时间窗内实际逐行判定的原始报文行数 */
        private int scanned;
        private boolean truncated;

        public String getField() {
            return field;
        }

        public String getRegionKey() {
            return regionKey;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public int getRowCount() {
            return rowCount;
        }

        public int getScanned() {
            return scanned;
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    /** 查询结果 */
    public static class HistoryResult {
        private String key;
        private String keyField;
        private List<Map<String, Object>> rows = new ArrayList<>();
        private boolean truncated;

        public String getKey() {
            return key;
        }

        public String getKeyField() {
            return keyField;
        }

        public List<Map<String, Object>> getRows() {
            return rows;
        }

        public int getRowCount() {
            return rows.size();
        }

        public boolean isTruncated() {
            return truncated;
        }
    }

    /**
     * 查某目标的历史轨迹点，按 event_time 升序。
     *
     * @param projectId 项目 ID（校验数据集归属）
     * @param datasetId 数据集 ID
     * @param key       目标标识值（如 mmsi）
     * @param startMs   起始时间（epoch 毫秒，可空=不限）
     * @param endMs     结束时间（epoch 毫秒，可空=不限）
     * @param limit     期望返回行数（可空=取 flink.tsdb.history-max-rows）
     */
    public HistoryResult queryHistory(String projectId, String datasetId, String key,
                                      Long startMs, Long endMs, Integer limit) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少目标标识");
        }
        StreamDatasetMeta meta = requireStreamDataset(projectId, datasetId);
        FlinkJobProperties.Tsdb tsdb = requireTsdb();

        int maxRows = resolveMaxRows(limit);
        // 多取一行用于判断是否被截断（truncated），返回时丢掉
        int probeRows = maxRows + 1;

        StringBuilder sql = new StringBuilder();
        // 键名内联为字面量而非占位符：`jsonb ->> ?` 会让 PG 在 `->> text` 与 `->> integer` 之间
        // 判定不出唯一算子而报「operator is not unique」。键名已过 ^[a-zA-Z0-9_]+$ 白名单，无注入风险。
        sql.append("SELECT event_time, payload FROM ").append(tsdb.getTable())
           .append(" WHERE dataset_id = ? AND payload ->> '").append(meta.streamKey).append("' = ?");
        if (startMs != null) {
            sql.append(" AND event_time >= ?");
        }
        if (endMs != null) {
            sql.append(" AND event_time <= ?");
        }
        sql.append(" ORDER BY event_time ASC LIMIT ?");

        HistoryResult result = new HistoryResult();
        result.key = key.trim();
        result.keyField = meta.streamKey;

        try (Connection conn = openConnection(tsdb);
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, datasetId);
            ps.setString(i++, key.trim());
            if (startMs != null) {
                ps.setTimestamp(i++, new Timestamp(startMs));
            }
            if (endMs != null) {
                ps.setTimestamp(i++, new Timestamp(endMs));
            }
            ps.setInt(i, probeRows);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (result.rows.size() >= maxRows) {
                        result.truncated = true;
                        break;
                    }
                    Map<String, Object> row = parsePayload(rs.getString("payload"));
                    if (row == null || !normalizeLngLat(row)) {
                        // 坐标缺失/非数值的点无法画在轨迹上，跳过（不计入 rowCount）
                        continue;
                    }
                    Timestamp ts = rs.getTimestamp("event_time");
                    if (ts != null) {
                        row.put("_eventTime", ts.toInstant().toString());
                    }
                    result.rows.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("[STREAM_HISTORY] query failed datasetId={} key={}", datasetId, key, e);
            throw new RuntimeException("查询历史轨迹失败：" + e.getMessage(), e);
        }
        return result;
    }

    /**
     * 目标位置查询（N1）：给定目标编号（精确）或名称关键词（模糊），返回其最新一条报文的位置。
     *
     * 与历史轨迹共用连接/白名单校验；差异是这里的匹配字段（field）可由调用方指定，
     * 而 q（模糊）模式**不要求**数据集配 streamKey——返回的 key 此时为 null。
     *
     * @param projectId     项目 ID（校验数据集归属）
     * @param datasetId     流式数据集 ID
     * @param key           精确匹配值（编号，如 mmsi）；与 q 二选一
     * @param q             模糊关键词（名称片段）；与 key 二选一
     * @param field         匹配用的 payload 字段名；key 模式默认 streamKey，q 模式必填
     * @param windowMinutes 模糊模式的时间窗（分钟，默认 60）
     * @param limit         模糊模式最多返回的目标数（默认 20）
     */
    public TargetResult queryTargets(String projectId, String datasetId, String key, String q,
                                     String field, Integer windowMinutes, Integer limit) {
        if (datasetId == null || datasetId.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少 datasetId");
        }
        boolean hasKey = key != null && !key.trim().isEmpty();
        boolean hasQ = q != null && !q.trim().isEmpty();
        if (hasKey == hasQ) {
            throw new IllegalArgumentException("key 与 q 必须二选一");
        }
        if (hasQ && (field == null || field.trim().isEmpty())) {
            throw new IllegalArgumentException("模糊搜索必须指定 field（要匹配的 payload 字段名）");
        }

        // 数据集校验：比历史轨迹宽松一档——q 模式不要求配 streamKey（名称字段无处可配，见 §10 风险 5）
        Dataset ds = datasetRepository.findById(datasetId);
        if (ds == null || (projectId != null && !projectId.isEmpty()
                && !projectId.equals(ds.getProjectId()))) {
            throw new DatasetNotFound("数据集不存在: " + datasetId);
        }
        Map<String, Object> meta = parseMeta(ds.getMetadata());
        if (meta == null || !Boolean.TRUE.equals(meta.get("stream"))) {
            throw new IllegalArgumentException("非流式数据集，无实时目标：" + datasetId);
        }
        String streamKey = trimToNull(meta.get("streamKey"));
        if (streamKey != null && !SAFE_IDENT.matcher(streamKey).matches()) {
            // 配置里的字段名不可信：非法就当作未配置（返回的 key 为 null），不改抛异常挡掉整个查询
            log.warn("[STREAM_TARGETS] illegal streamKey in metadata datasetId={}", datasetId);
            streamKey = null;
        }

        String matchField = trimToNull(field);
        if (matchField == null) {
            matchField = streamKey;
        }
        if (matchField == null) {
            throw new IllegalArgumentException("该数据集未配置目标标识字段（streamKey），必须显式指定 field");
        }
        if (!SAFE_IDENT.matcher(matchField).matches()) {
            throw new IllegalArgumentException("字段名非法：" + matchField);
        }

        TargetResult result = new TargetResult();
        result.mode = hasKey ? "exact" : "fuzzy";
        result.field = matchField;

        FlinkJobProperties.Tsdb tsdb = requireTsdb();

        StringBuilder sql = new StringBuilder();
        // 字段名内联为字面量而非占位符：`jsonb ->> ?` 会让 PG 在 `->> text` 与 `->> integer`
        // 之间判定不出唯一算子而报「operator is not unique」（见 queryHistory 同款注释）。
        // 字段名已过 ^[a-zA-Z0-9_]+$ 白名单，无注入风险。
        sql.append("SELECT event_time, payload FROM ").append(tsdb.getTable())
           .append(" WHERE dataset_id = ? AND payload ->> '").append(matchField).append("'");
        boolean fuzzy = !hasKey;
        if (fuzzy) {
            sql.append(" ILIKE ? ESCAPE '\\'");
            sql.append(" AND event_time >= ?");
        } else {
            sql.append(" = ?");
        }
        sql.append(" ORDER BY event_time DESC LIMIT ?");

        int wantLimit = clamp(limit == null ? 20 : limit, 1, 200);
        // 模糊模式要多取一些原始行：同一目标可能在一个时间窗里有多条报文，去重后才得到目标数
        int fetchLimit = fuzzy ? Math.min(Math.max(wantLimit * 20, wantLimit), 2000) : 1;

        Map<String, Map<String, Object>> deduped = new LinkedHashMap<>();
        boolean hitFetchCap = false;

        try (Connection conn = openConnection(tsdb);
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int i = 1;
            ps.setString(i++, datasetId);
            if (fuzzy) {
                ps.setString(i++, "%" + escapeLike(q.trim()) + "%");
                ps.setTimestamp(i++, new Timestamp(System.currentTimeMillis() - windowMs(windowMinutes)));
            } else {
                ps.setString(i++, key.trim());
            }
            ps.setInt(i, fetchLimit);

            try (ResultSet rs = ps.executeQuery()) {
                int fetched = 0;
                while (rs.next()) {
                    fetched++;
                    Map<String, Object> payload = parsePayload(rs.getString("payload"));
                    if (payload == null) {
                        continue;
                    }
                    Double lng = toDouble(payload.get("lng"));
                    Double lat = toDouble(payload.get("lat"));
                    if (lng == null || lat == null) {
                        // 坐标缺失的点无法聚焦/上图，跳过（不计入 rowCount）
                        continue;
                    }
                    // key 恒取 streamKey 字段的值（本轮搜索字段无关），它是后续操作的凭据
                    String targetKey = streamKey == null ? null : stringify(payload.get(streamKey));

                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", targetKey);
                    row.put("lng", lng);
                    row.put("lat", lat);
                    Timestamp ts = rs.getTimestamp("event_time");
                    row.put("eventTime", ts == null ? null : ts.toInstant().toString());
                    row.put("payload", payload);

                    // 按目标去重，只留最新一条（结果已按 event_time DESC，先到者即最新）
                    String identity = targetKey != null ? "k:" + targetKey
                            : "v:" + stringify(payload.get(matchField));
                    if (!deduped.containsKey(identity)) {
                        if (deduped.size() >= wantLimit) {
                            result.truncated = true;
                            break;
                        }
                        deduped.put(identity, row);
                    }
                }
                hitFetchCap = fetched >= fetchLimit;
            }
        } catch (Exception e) {
            log.warn("[STREAM_TARGETS] query failed datasetId={} key={} q={}", datasetId, key, q, e);
            throw new RuntimeException("查询目标位置失败：" + e.getMessage(), e);
        }

        result.rows.addAll(deduped.values());
        // 取回的行数正好等于上限 → 库里可能还有更多目标没取全
        if (hitFetchCap && result.rows.size() >= wantLimit) {
            result.truncated = true;
        }
        return result;
    }

    /**
     * 区域筛选（N8，v1.2）：取该数据集各目标的<b>最新位置</b>，返回落在给定矩形内的那些。
     *
     * <p>与 queryTargets 的差异：不按编号/名称筛，而是「先取每个目标的最新一条，再看它在不在框里」。
     * 语义上等价于「此刻这片海域有哪些目标」，而不是「历史上进过这片海域的目标」——后者要扫全部历史点，
     * 是另一个量级的查询。
     *
     * <p><b>为什么在内存里判 bbox、不写进 SQL</b>：目标标识与经纬度都埋在 payload jsonb 里，
     * 时序库上没有可用的空间索引（建索引要按具体字段名，而字段名各数据集不同），
     * 把 bbox 写进 SQL 只能全表扫再加 jsonb 逐行过滤，不比取回后在 Java 里判快，还难给达梦/PG 通用。
     * 所以 SQL 只做 dataset_id + 时间窗 + 按时间倒序，剩下在内存里算。
     *
     * <p>精度：<b>矩形</b>。渤海、南海这类区域本身就是按矩形配的（regions.json），
     * 用矩形判定与字典口径一致；它的偏差是「框内的陆地/邻区目标也会被算进来」，属于已知过包含。
     *
     * @param projectId     项目 ID（校验数据集归属）
     * @param datasetId     流式数据集 ID（必须配了 streamKey，否则无法识别目标）
     * @param regionKey     区域 key，仅用于回填到每行（可空）
     * @param bbox          矩形 [minLng, minLat, maxLng, maxLat]（调用方已校验）
     * @param windowMinutes 只取最近多少分钟的报文（默认 60，钳制 [1, 10080]）
     * @param limit         最多返回的目标数（默认 200，钳制 [1, 2000]）
     * @param countOnly     只数个数不返回行（rows 为空、rowCount 仍为命中数）
     * @param scanLimit     最多扫描的原始报文行数（默认 20000，钳制 [1, ABSOLUTE_MAX_ROWS]）
     */
    public RegionTargetResult queryTargetsInRegion(String projectId, String datasetId, String regionKey,
                                                   List<Double> bbox, Integer windowMinutes, Integer limit,
                                                   boolean countOnly, Integer scanLimit) {
        if (bbox == null || bbox.size() != 4) {
            throw new IllegalArgumentException("缺少 bbox");
        }
        StreamDatasetMeta meta = requireStreamDataset(projectId, datasetId);
        FlinkJobProperties.Tsdb tsdb = requireTsdb();

        int wantLimit = clamp(limit == null ? 200 : limit, 1, 2000);
        int scan = clamp(scanLimit == null ? DEFAULT_SCAN_ROWS : scanLimit, 1, ABSOLUTE_MAX_ROWS);

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT event_time, payload FROM ").append(tsdb.getTable())
           .append(" WHERE dataset_id = ? AND event_time >= ?")
           .append(" ORDER BY event_time DESC LIMIT ?");

        RegionTargetResult result = new RegionTargetResult();
        result.field = meta.streamKey;
        result.regionKey = regionKey;

        int scanned = 0;
        int matched = 0;
        boolean scanCapped = false;
        // 每个目标只认第一条（结果按 event_time DESC，先到者即最新）；用 Map 当已见集合
        Map<String, Boolean> seen = new LinkedHashMap<>();

        try (Connection conn = openConnection(tsdb);
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setString(1, datasetId);
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis() - windowMs(windowMinutes)));
            // 多取一行用于判断是否被扫描上限截断（与 queryHistory 的 probeRows 同款手法）
            ps.setInt(3, scan + 1);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (scanned >= scan) {
                        scanCapped = true;
                        break;
                    }
                    scanned++;
                    Map<String, Object> payload = parsePayload(rs.getString("payload"));
                    if (payload == null) {
                        continue;
                    }
                    Double lng = toDouble(payload.get("lng"));
                    Double lat = toDouble(payload.get("lat"));
                    if (lng == null || lat == null) {
                        continue;
                    }
                    String targetKey = stringify(payload.get(meta.streamKey));
                    if (targetKey == null) {
                        // 没有标识就无法判重、也无法回报给智能体去选中它
                        continue;
                    }
                    if (seen.put(targetKey, Boolean.TRUE) != null) {
                        // 该目标已有更新的位置被处理过，这条是旧报文
                        continue;
                    }
                    if (!inBbox(lng, lat, bbox)) {
                        continue;
                    }
                    matched++;
                    if (countOnly) {
                        continue;
                    }
                    if (result.rows.size() >= wantLimit) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", targetKey);
                    row.put("lng", lng);
                    row.put("lat", lat);
                    Timestamp ts = rs.getTimestamp("event_time");
                    row.put("eventTime", ts == null ? null : ts.toInstant().toString());
                    row.put("payload", payload);
                    row.put("region", regionKey);
                    result.rows.add(row);
                }
            }
        } catch (Exception e) {
            log.warn("[STREAM_TARGETS] in-region query failed datasetId={} region={}", datasetId, regionKey, e);
            throw new RuntimeException("按区域查询目标失败：" + e.getMessage(), e);
        }

        result.scanned = scanned;
        result.rowCount = countOnly ? matched : result.rows.size();
        // 截断有两种成因：扫描窗口用尽（可能还有目标没扫到）、命中数超过 limit（行没取全）
        result.truncated = scanCapped || (!countOnly && matched > result.rows.size());
        return result;
    }

    /**
     * 该数据集在时序库里的实际数据时间窗（min/max event_time）。
     * 走 (dataset_id, event_time) 索引，实测毫秒级。用于前端时间选择框的默认/兜底提示。
     *
     * @return {minTime, maxTime}（无数据时为 null）
     */
    public Map<String, Object> queryRange(String projectId, String datasetId) {
        StreamDatasetMeta meta = requireStreamDataset(projectId, datasetId);
        FlinkJobProperties.Tsdb tsdb = requireTsdb();

        String sql = "SELECT min(event_time) AS min_t, max(event_time) AS max_t FROM "
                + tsdb.getTable() + " WHERE dataset_id = ?";

        Map<String, Object> out = new LinkedHashMap<>();
        try (Connection conn = openConnection(tsdb);
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, datasetId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Timestamp min = rs.getTimestamp("min_t");
                    Timestamp max = rs.getTimestamp("max_t");
                    out.put("minTime", min == null ? null : min.toInstant().toString());
                    out.put("maxTime", max == null ? null : max.toInstant().toString());
                }
            }
        } catch (Exception e) {
            log.warn("[STREAM_HISTORY] range failed datasetId={}", datasetId, e);
            throw new RuntimeException("查询数据时间范围失败：" + e.getMessage(), e);
        }
        out.put("keyField", meta.streamKey);
        return out;
    }

    /**
     * 矩形包含判定（含边界），N8 的筛选内核。
     *
     * <p>单独拎出来是为了能脱离数据库单测——它是一个纯函数，边界（恰好落在框上）算命中，
     * 因为目标是「在这片海域里」，贴边的目标不该被漏掉。
     */
    static boolean inBbox(double lng, double lat, List<Double> bbox) {
        return lng >= bbox.get(0) && lng <= bbox.get(2) && lat >= bbox.get(1) && lat <= bbox.get(3);
    }

    // ==================== 校验与连接 ====================

    /** 流式数据集 + 目标标识字段的内存投影 */
    private static class StreamDatasetMeta {
        String streamKey;
    }

    /** 校验数据集存在、属于该项目、是流式数据集且配了 streamKey */
    private StreamDatasetMeta requireStreamDataset(String projectId, String datasetId) {
        Dataset ds = datasetRepository.findById(datasetId);
        if (ds == null || (projectId != null && !projectId.isEmpty()
                && !projectId.equals(ds.getProjectId()))) {
            throw new DatasetNotFound("数据集不存在: " + datasetId);
        }
        Map<String, Object> meta = parseMeta(ds.getMetadata());
        if (meta == null || !Boolean.TRUE.equals(meta.get("stream"))) {
            throw new IllegalArgumentException("非流式数据集，无历史轨迹：" + datasetId);
        }
        Object key = meta.get("streamKey");
        String streamKey = key == null ? null : key.toString().trim();
        if (streamKey == null || streamKey.isEmpty()) {
            throw new IllegalArgumentException("该数据集未配置目标标识字段（streamKey），无法查询历史轨迹");
        }
        if (!SAFE_IDENT.matcher(streamKey).matches()) {
            throw new IllegalArgumentException("目标标识字段名非法：" + streamKey);
        }
        StreamDatasetMeta out = new StreamDatasetMeta();
        out.streamKey = streamKey;
        return out;
    }

    private FlinkJobProperties.Tsdb requireTsdb() {
        FlinkJobProperties.Tsdb tsdb = flinkJobProperties.getTsdb();
        if (tsdb.getUrl() == null || tsdb.getUrl().trim().isEmpty()) {
            throw new TsdbUnavailable("未配置时序库（flink.tsdb.url），无法查询历史轨迹");
        }
        if (tsdb.getTable() == null || !SAFE_IDENT.matcher(tsdb.getTable()).matches()) {
            throw new IllegalArgumentException("时序库表名非法：" + tsdb.getTable());
        }
        return tsdb;
    }

    private int resolveMaxRows(Integer limit) {
        int configured = flinkJobProperties.getTsdb().getHistoryMaxRows();
        int ceiling = configured > 0 ? Math.min(configured, ABSOLUTE_MAX_ROWS) : ABSOLUTE_MAX_ROWS;
        int wanted = (limit == null || limit <= 0) ? ceiling : limit;
        return Math.max(1, Math.min(wanted, ceiling));
    }

    private Connection openConnection(FlinkJobProperties.Tsdb tsdb) throws Exception {
        Class.forName("org.postgresql.Driver");
        String user = tsdb.getUser();
        String password = tsdb.getPassword();
        if (user == null || user.trim().isEmpty()) {
            return DriverManager.getConnection(tsdb.getUrl());
        }
        return DriverManager.getConnection(tsdb.getUrl(), user, password);
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 轨迹点必须有可用的经纬度（payload 顶层 lng/lat，由 Flink 作业合成）。
     * 顺带把数字字符串归一成 Double 写回，保证前端拿到的永远是数值。
     */
    private boolean normalizeLngLat(Map<String, Object> row) {
        Double lng = toDouble(row.get("lng"));
        Double lat = toDouble(row.get("lat"));
        if (lng == null || lat == null) {
            return false;
        }
        row.put("lng", lng);
        row.put("lat", lat);
        return true;
    }

    private static Double toDouble(Object v) {
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

    private static String trimToNull(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** payload 里的原始值转成字符串，用作目标标识（Integer 245272000 → "245272000"） */
    private static String stringify(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            Double d = ((Number) v).doubleValue();
            if (d == Math.floor(d) && !d.isInfinite()) {
                return String.valueOf(((Number) v).longValue());
            }
        }
        return String.valueOf(v);
    }

    /** 用户输入里的 % / _ 是普通字符，转义掉才不会变成通配符（配合 SQL 里的 ESCAPE '\'） */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 模糊搜索时间窗（毫秒）：默认 60 分钟，钳制在 [1 分钟, 7 天] */
    private static long windowMs(Integer windowMinutes) {
        int minutes = clamp(windowMinutes == null ? 60 : windowMinutes, 1, 7 * 24 * 60);
        return minutes * 60000L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
