package com.antv.l7vp.flink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.configuration.Configuration;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式数据集 Flink 作业。
 *
 * Kafka ──(KafkaSource)──▶ 加工(map) ──┬─▶ 批量异步回推 java-server /stream/push ──▶ WS ──▶ 前端图层
 *                                     └─▶ (可选) TimescaleDbSink 批量写时序库 TimescaleDB
 *
 * 由 java-server 通过 Flink REST API 提交（作业名固定 l7vp-stream-{datasetId}，供幂等去重）。
 * 启动参数（java-server 注入，key=value 空格分隔，值内不含空格）：
 *   projectId, datasetId, topic, bootstrapServers, groupId,
 *   pushUrl (完整 /stream/push 地址), pushToken (可为空), batchSize, batchMs
 *   autoOffsetReset (可选, earliest|latest，默认 latest=生产实时只读新消息)
 *   可选落时序库：tsdbEnabled=true 时还需 tsdbUrl, tsdbUser, tsdbPassword, tsdbTable, tsdbTimeField
 *   可选键化模式（每船/每目标一个实时点）：streamKey=标识字段列名（如 mmsi/flight/hex），
 *     maxWindow=全量船表容量上限（默认 1000，与后端环形缓冲上限对齐）
 *
 * 数据契约：每条 Kafka 消息为「一个 JSON 行对象，键 = 数据集列名」。
 *   - 非键化（未配 streamKey）：回推 {"rows":[...],"op":"append"}，原始最近 N 条。
 *   - 键化（配 streamKey）：keyBy(streamKey) → 每键最新(仅变化下行) → 单实例 LRU 船表(≤maxWindow)，
 *     周期回推 {"rows":[全量船表],"op":"replace"}——后端清缓冲整表写入，前端 replace 渲染，每船一个点。
 *
 * ⚠️ 本类是「接入骨架」。大数据量的真实加工（窗口/join/聚合）请写在 {@link EnrichFunction#map}
 *   或 DataStream 算子链中。
 */
public class StreamDatasetJob {

    private static final Logger LOG = LoggerFactory.getLogger(StreamDatasetJob.class);

    /** Kafka 列名→行的最大单批回推条数 */
    private static final int DEFAULT_BATCH_SIZE = 200;
    /** 单批回推的攒批窗口（毫秒） */
    private static final long DEFAULT_BATCH_MS = 500L;
    /** 键化模式：全量船表默认容量上限（与后端 L7VP_STREAM_MAX_WINDOW 默认一致） */
    private static final int DEFAULT_MAX_WINDOW = 1000;

    public static void main(String[] args) throws Exception {
        Map<String, String> cfg = parseArgs(args);

        String projectId = require(cfg, "projectId");
        String datasetId = require(cfg, "datasetId");
        String topic = require(cfg, "topic");
        String bootstrapServers = require(cfg, "bootstrapServers");
        String groupId = cfg.getOrDefault("groupId", "l7vp-stream-" + datasetId);
        String pushUrl = require(cfg, "pushUrl");
        String pushToken = cfg.getOrDefault("pushToken", "");
        int batchSize = Integer.parseInt(cfg.getOrDefault("batchSize", String.valueOf(DEFAULT_BATCH_SIZE)));
        long batchMs = Long.parseLong(cfg.getOrDefault("batchMs", String.valueOf(DEFAULT_BATCH_MS)));
        // 键化模式：streamKey 非空 → 每船最新聚合；maxWindow 为全量船表容量（≤ 后端缓冲上限保持一致）
        String streamKey = cfg.getOrDefault("streamKey", "").trim();
        int maxWindow = Integer.parseInt(cfg.getOrDefault("maxWindow", String.valueOf(DEFAULT_MAX_WINDOW)));

        // 消费起始位置：earliest=测试/历史回放全量重读；latest=生产实时(默认，只读新消息)
        String autoOffsetReset = cfg.getOrDefault("autoOffsetReset", "latest");
        OffsetsInitializer offsets;
        switch (autoOffsetReset.trim().toLowerCase(Locale.ROOT)) {
            case "earliest":
                offsets = OffsetsInitializer.earliest();
                break;
            case "latest":
            default:
                offsets = OffsetsInitializer.latest();
                break;
        }

        LOG.info("[L7VP-STREAM] 启动作业 projectId={}, datasetId={}, topic={}, bootstrap={}, groupId={}",
            projectId, datasetId, topic, bootstrapServers, groupId);
        LOG.info("[L7VP-STREAM] pushUrl={}, batchSize={}, batchMs={}, autoOffsetReset={}, keyedMode(streamKey)={}, maxWindow={}",
            pushUrl, batchSize, batchMs, autoOffsetReset.trim().toLowerCase(Locale.ROOT), streamKey, maxWindow);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

        KafkaSource<String> source = KafkaSource.<String>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(offsets)
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();

        DataStream<String> stream = env
            .fromSource(source, WatermarkStrategy.noWatermarks(), "kafka-source:" + topic)
            .name("l7vp-kafka-source")
            .map(new EnrichFunction())
            .name("l7vp-transform")
            // 加工后返回 null 的消息丢弃（解析失败/不关心的记录在此过滤）
            .filter(new org.apache.flink.api.common.functions.FilterFunction<String>() {
                @Override
                public boolean filter(String value) {
                    return value != null && !value.isEmpty();
                }
            })
            .name("l7vp-filter-null");

        if (streamKey.isEmpty()) {
            // 非键化：原始最近 N 条（append），行为不变
            stream.addSink(new BatchHttpPushSink(pushUrl, pushToken, batchSize, batchMs))
                .name("l7vp-http-push");
        } else {
            // 键化：keyBy(标识字段) → 每键最新(仅内容变化下行) → 单实例 LRU 全量船表 op=replace 回推
            // 全量"每船最新"表只能落在单个并行实例；回放量级足够，量级极大时再演进为 keyed-state + 下游按键合并。
            stream
                .keyBy(new KeyFieldSelector(streamKey))
                .process(new LatestPerKeyFn(streamKey))
                .name("l7vp-latest-per-key")
                .addSink(new FleetReplaceSink(pushUrl, pushToken, streamKey, maxWindow, batchMs))
                .setParallelism(1)
                .name("l7vp-fleet-replace");
        }

        // —— 可选：时序库落库（TimescaleDB/PostGIS）。java-server 侧 flink.tsdb.enabled=true 且 url 非空时开启 ——
        boolean tsdbEnabled = Boolean.parseBoolean(cfg.getOrDefault("tsdbEnabled", "false"));
        String tsdbUrl = cfg.get("tsdbUrl");
        if (tsdbEnabled && tsdbUrl != null && !tsdbUrl.isEmpty()) {
            stream.addSink(new TimescaleDbSink(
                    datasetId,
                    tsdbUrl,
                    cfg.getOrDefault("tsdbUser", ""),
                    cfg.getOrDefault("tsdbPassword", ""),
                    cfg.getOrDefault("tsdbTable", "stream_events"),
                    cfg.getOrDefault("tsdbTimeField", ""),
                    batchSize, batchMs))
                .name("l7vp-tsdb-sink");
            LOG.info("[L7VP-STREAM] 时序库落库已启用 url={}, table={}",
                tsdbUrl, cfg.getOrDefault("tsdbTable", "stream_events"));
        }

        env.execute("l7vp-stream-" + datasetId);
    }

    /**
     * 解析 Kafka 消息 → 行 JSON。
     * 默认行为：把消息当作「键=列名的单行对象」透传，同时做一次 JSON 合法性校验；
     * 解析失败返回 null（被过滤）。真实加工请在此补充窗口/聚合/字段映射。
     */
    public static class EnrichFunction implements MapFunction<String, String>, Serializable {
        private static final long serialVersionUID = 1L;
        private transient ObjectMapper mapper;

        @Override
        public String map(String raw) throws Exception {
            if (mapper == null) {
                mapper = new ObjectMapper();
            }
            try {
                // 反序列化 → 供加工；默认原样返回（键即数据集列名）
                @SuppressWarnings("unchecked")
                Map<String, Object> row = mapper.readValue(raw, Map.class);
                // ===== 真实加工：把 shadowbroker_replay 这类 GeoJSON 行展开成平铺行 =====
                // 契约：行 JSON 的顶层键必须是数据集列名（lng/lat 是图层取坐标的默认列）。
                // 消息把经纬度嵌在 geom:{coordinates:[lng,lat]} 里，没有顶层 lng/lat，
                // 直接透传会让前端拿不到坐标、画不出点。这里统一展开：
                //   lng ← geom.coordinates[0]；lat ← geom.coordinates[1]
                //   其余顶层标量字段（time/category/mmsi/name/speed/...）原样平铺保留；
                //   嵌套对象（geom 等）不展开、不输出。
                boolean hasFlatLngLat = row.containsKey("lng") && row.containsKey("lat");
                Object geom = row.get("geom");
                if (hasFlatLngLat || !(geom instanceof Map)) {
                    // 已有顶层 lng/lat（其它数据源）或没有可解析的 geom：维持原样透传
                    return mapper.writeValueAsString(row);
                }
                Map<String, Object> out = new LinkedHashMap<>();
                Object coordsObj = ((Map<?, ?>) geom).get("coordinates");
                if (coordsObj instanceof List && ((List<?>) coordsObj).size() >= 2) {
                    List<?> coords = (List<?>) coordsObj;
                    out.put("lng", coords.get(0));   // GeoJSON 坐标顺序 = [经度, 纬度]
                    out.put("lat", coords.get(1));
                }
                for (Map.Entry<String, Object> e : row.entrySet()) {
                    Object v = e.getValue();
                    if ("geom".equals(e.getKey()) || v == null) {
                        continue;                    // 嵌套对象 / 空值不展开
                    }
                    if (v instanceof Map || v instanceof List) {
                        continue;
                    }
                    out.put(e.getKey(), v);
                }
                return mapper.writeValueAsString(out);
            } catch (Exception e) {
                LOG.debug("[L7VP-STREAM] 解析失败跳过消息: {}", raw);
                return null;
            }
        }
    }

    /**
     * 批量异步 HTTP 回推 sink。
     * invoke 只入队不阻塞（高吞吐）；后台线程按 batchMs 周期或攒满 batchSize 触发一次 POST。
     */
    public static class BatchHttpPushSink extends RichSinkFunction<String> {
        private static final long serialVersionUID = 1L;

        private final String pushUrl;
        private final String pushToken;
        private final int batchSize;
        private final long batchMs;

        private transient ConcurrentLinkedQueue<String> pending;
        private transient ScheduledExecutorService scheduler;
        private transient CloseableHttpClient httpClient;
        private transient ObjectMapper mapper;

        public BatchHttpPushSink(String pushUrl, String pushToken, int batchSize, long batchMs) {
            this.pushUrl = pushUrl;
            this.pushToken = pushToken;
            this.batchSize = batchSize;
            this.batchMs = batchMs;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            pending = new ConcurrentLinkedQueue<>();
            mapper = new ObjectMapper();

            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            cm.setMaxTotal(2);
            cm.setDefaultMaxPerRoute(2);
            RequestConfig reqCfg = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(10000)
                .setConnectionRequestTimeout(3000)
                .build();
            httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(reqCfg)
                .build();

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "l7vp-http-push-flusher");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(batchMs, 50L);
            scheduler.scheduleAtFixedRate(this::flush, period, period, TimeUnit.MILLISECONDS);
        }

        @Override
        public void invoke(String row, Context context) {
            if (row == null) {
                return;
            }
            pending.add(row);
            // 攒满一个批次即安排一次即时冲刷；周期任务每 batchMs 兜底冲刷一次
            if (pending.size() >= batchSize) {
                scheduler.execute(this::flush);
            }
        }

        private void flush() {
            List<String> batch = new ArrayList<>(batchSize);
            String row;
            while ((row = pending.poll()) != null && batch.size() < batchSize) {
                batch.add(row);
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                StringBuilder rowsJson = new StringBuilder();
                for (int i = 0; i < batch.size(); i++) {
                    if (i > 0) {
                        rowsJson.append(',');
                    }
                    rowsJson.append(batch.get(i).trim());
                }
                String body = "{\"rows\":[" + rowsJson + "],\"op\":\"append\"}";

                HttpPost post = new HttpPost(pushUrl);
                post.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
                if (pushToken != null && !pushToken.isEmpty()) {
                    post.setHeader("X-Stream-Token", pushToken);
                }
                post.setEntity(new StringEntity(body, "UTF-8"));

                try (CloseableHttpResponse resp = httpClient.execute(post)) {
                    int code = resp.getStatusLine().getStatusCode();
                    if (code >= 200 && code < 300) {
                        LOG.debug("[L7VP-STREAM] 回推成功 rows={}", batch.size());
                    } else {
                        LOG.warn("[L7VP-STREAM] 回推失败 status={}, rows={}, body={}",
                            code, batch.size(), EntityUtils.toString(resp.getEntity()));
                    }
                }
            } catch (Exception e) {
                // 网络抖动：丢弃本批并记录（生产可按需改为重试/落盘兜底）
                LOG.warn("[L7VP-STREAM] 回推异常 rows={} err={}", batch.size(), e.toString());
            }
        }

        @Override
        public void close() throws Exception {
            super.close();
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (httpClient != null) {
                httpClient.close();
            }
        }
    }

    /**
     * 从行 JSON 提取标识字段的规范化文本键：数值按十进制字符串（如 mmsi 数字/字符串同键），文本原样。
     * 行无该字段或解析失败返回 null（调用方决定丢弃或归入占位组）。
     */
    private static String extractKey(String row, String keyField, ObjectMapper mapper) {
        try {
            JsonNode root = mapper.readTree(row);
            JsonNode kv = root.get(keyField);
            if (kv == null || kv.isNull()) {
                return null;
            }
            return kv.isNumber() ? kv.numberValue().toString() : kv.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * keyBy 分组键：按标识字段值分组；缺键行统一归入空组（LatestPerKeyFn 不会下行它们）。
     */
    public static class KeyFieldSelector implements KeySelector<String, String> {
        private static final long serialVersionUID = 1L;

        private final String keyField;
        private transient ObjectMapper m;

        public KeyFieldSelector(String keyField) {
            this.keyField = keyField;
        }

        private ObjectMapper jm() {
            if (m == null) {
                m = new ObjectMapper();
            }
            return m;
        }

        @Override
        public String getKey(String row) {
            String k = extractKey(row, keyField, jm());
            return k == null ? "" : k;
        }
    }

    /**
     * 每键最新状态：keyed ValueState 记住该键已下行的最新行，仅当新行内容不同才下行。
     * 效果：同一艘船/目标的重复或旧报文不下行，减少下游船表写入与整表回推的次数。
     */
    public static class LatestPerKeyFn extends KeyedProcessFunction<String, String, String> {
        private static final long serialVersionUID = 1L;

        private final String keyField;
        private transient ValueState<String> last;
        private transient ObjectMapper mapper;

        public LatestPerKeyFn(String keyField) {
            this.keyField = keyField;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            last = getRuntimeContext().getState(
                new ValueStateDescriptor<>("latest-" + keyField, TypeInformation.of(String.class)));
            mapper = new ObjectMapper();
        }

        @Override
        public void processElement(String row, Context ctx, Collector<String> out) throws Exception {
            if (row == null || row.isEmpty()) {
                return;
            }
            if (extractKey(row, keyField, mapper) == null) {
                // 无标识字段的行无法按键，丢弃（不含 lng/lat 的脏行也走不到这里）
                return;
            }
            String prev = last.value();
            if (row.equals(prev)) {
                return;   // 该键内容未变：不重复下行
            }
            last.update(row);
            out.collect(row);
        }
    }

    /**
     * 每船最新聚合 sink（必须单实例 parallelism=1）。
     *
     * 维护 access-order LRU 的「标识键 → 最新行」全量船表，容量 maxWindow；
     * 后台线程每 batchMs，在船表有变化时把整表以 {"rows":[...],"op":"replace"} 回推 java-server
     * /stream/push——push 端点清空并整表写入环形缓冲 → WS 广播 → 前端 replace 渲染「每船一个实时点」。
     *
     * 掉线语义：船停报后仍保留最后位置，仅当船表超过 maxWindow 时挤出最久未更新的键；
     * 该键若再发报则重新入表（最近端）。
     * 网络失败不清 dirty，下一周期自动重发（replace 幂等，自愈）。
     */
    public static class FleetReplaceSink extends RichSinkFunction<String> {
        private static final long serialVersionUID = 1L;

        private final String pushUrl;
        private final String pushToken;
        private final String keyField;
        private final int maxWindow;
        private final long batchMs;

        private transient LinkedHashMap<String, String> fleet;
        private transient volatile boolean dirty;
        private transient ScheduledExecutorService scheduler;
        private transient CloseableHttpClient httpClient;
        private transient ObjectMapper mapper;

        public FleetReplaceSink(String pushUrl, String pushToken, String keyField, int maxWindow, long batchMs) {
            this.pushUrl = pushUrl;
            this.pushToken = pushToken;
            this.keyField = keyField;
            this.maxWindow = maxWindow;
            this.batchMs = batchMs;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            mapper = new ObjectMapper();
            dirty = false;
            // accessOrder=true：查询/更新即移到最近端；超过 maxWindow 挤掉最久未更新的键
            fleet = new LinkedHashMap<String, String>(128, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > maxWindow;
                }
            };

            PoolingHttpClientConnectionManager cm = new PoolingHttpClientConnectionManager();
            cm.setMaxTotal(2);
            cm.setDefaultMaxPerRoute(2);
            RequestConfig reqCfg = RequestConfig.custom()
                .setConnectTimeout(3000)
                .setSocketTimeout(10000)
                .setConnectionRequestTimeout(3000)
                .build();
            httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setDefaultRequestConfig(reqCfg)
                .build();

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "l7vp-fleet-replace-flusher");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(batchMs, 50L);
            scheduler.scheduleAtFixedRate(this::flush, period, period, TimeUnit.MILLISECONDS);
        }

        @Override
        public void invoke(String row, Context context) {
            if (row == null || row.isEmpty()) {
                return;
            }
            String key = extractKey(row, keyField, mapper);
            if (key == null) {
                return;
            }
            synchronized (fleet) {
                fleet.put(key, row);
                dirty = true;
            }
        }

        private void flush() {
            List<String> snapshot;
            synchronized (fleet) {
                if (!dirty || fleet.isEmpty()) {
                    return;
                }
                // 保持 dirty=true 直到回推成功；成功后清位。invoke 并发写入会在清位后重新置脏，
                // 保证 flush 期间到达的新行不会被吞（下个周期重发）。
                snapshot = new ArrayList<>(fleet.values());
            }
            try {
                StringBuilder rowsJson = new StringBuilder();
                for (int i = 0; i < snapshot.size(); i++) {
                    if (i > 0) {
                        rowsJson.append(',');
                    }
                    rowsJson.append(snapshot.get(i).trim());
                }
                String body = "{\"rows\":[" + rowsJson + "],\"op\":\"replace\"}";

                HttpPost post = new HttpPost(pushUrl);
                post.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
                if (pushToken != null && !pushToken.isEmpty()) {
                    post.setHeader("X-Stream-Token", pushToken);
                }
                post.setEntity(new StringEntity(body, "UTF-8"));

                try (CloseableHttpResponse resp = httpClient.execute(post)) {
                    int code = resp.getStatusLine().getStatusCode();
                    if (code >= 200 && code < 300) {
                        synchronized (fleet) {
                            dirty = false;
                        }
                        LOG.info("[L7VP-STREAM] 船表整表回推成功 ships={}", snapshot.size());
                    } else {
                        LOG.warn("[L7VP-STREAM] 船表回推失败 status={}, ships={}, body={}",
                            code, snapshot.size(), EntityUtils.toString(resp.getEntity()));
                    }
                }
            } catch (Exception e) {
                // 保留 dirty=true，下一周期自动重发
                LOG.warn("[L7VP-STREAM] 船表回推异常 ships={} err={}", snapshot.size(), e.toString());
            }
        }

        @Override
        public void close() throws Exception {
            super.close();
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            if (httpClient != null) {
                httpClient.close();
            }
        }
    }

    /**
     * 可选：批量异步写 TimescaleDB/PostgreSQL 时序 sink。
     * 与 HTTP 回推同构：invoke 只入队不阻塞，后台线程按 batchMs 周期或攒满 batchSize 触发一次批量 INSERT。
     * 表结构（作业幂等自建 jsonb 超表）：stream_events(dataset_id text, event_time timestamptz, payload jsonb)。
     * event_time 优先取 payload 事件时间字段(tsdbTimeField，其次常见时间键)，取不到用作业入库时间。
     * 作业 at-least-once：重启/重放可能产生重复行（原始时序存储先接受）。
     * 写失败不丢数据：整批退回 retry 缓冲，按 500ms→10s 指数退避重连补写，缓冲超上限才丢弃并告警。
     */
    public static class TimescaleDbSink extends RichSinkFunction<String> {
        private static final long serialVersionUID = 1L;

        /** 常见事件时间键探测顺序（tsdbTimeField 未配置或取不到时） */
        private static final String[] TIME_KEY_CANDIDATES = {"t", "time", "timestamp", "ts", "event_time", "eventTime"};

        private final String datasetId;
        private final String url;
        private final String user;
        private final String password;
        private final String table;
        private final String timeField;
        private final int batchSize;
        private final long batchMs;

        private transient ConcurrentLinkedQueue<String> pending;
        /** 写失败批次的重试缓冲（优先于 pending 消费，避免 DB 抖动直接丢数据） */
        private transient ConcurrentLinkedQueue<String> retry;
        /** pending 的近似长度（ConcurrentLinkedQueue.size() 是 O(n)，不能放在 invoke 热路径上） */
        private transient AtomicInteger pendingCount;
        private transient ScheduledExecutorService scheduler;
        private transient ObjectMapper mapper;
        private transient Connection conn;
        private transient PreparedStatement insertPs;
        /** 连续失败次数，用于退避，避免 DB 不可用时每 batchMs 都重连+建索引 */
        private transient int failStreak;
        private transient long nextRetryAt;
        private transient long droppedRows;

        public TimescaleDbSink(String datasetId, String url, String user, String password,
                               String table, String timeField, int batchSize, long batchMs) {
            this.datasetId = datasetId;
            this.url = url;
            this.user = user;
            this.password = password;
            this.table = (table == null || table.isEmpty()) ? "stream_events" : table;
            this.timeField = timeField == null ? "" : timeField;
            this.batchSize = batchSize;
            this.batchMs = batchMs;
        }

        @Override
        public void open(Configuration parameters) throws Exception {
            super.open(parameters);
            pending = new ConcurrentLinkedQueue<>();
            retry = new ConcurrentLinkedQueue<>();
            pendingCount = new AtomicInteger();
            mapper = new ObjectMapper();
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "l7vp-tsdb-flusher");
                t.setDaemon(true);
                return t;
            });
            long period = Math.max(batchMs, 100L);
            scheduler.scheduleAtFixedRate(this::flush, period, period, TimeUnit.MILLISECONDS);
        }

        @Override
        public void invoke(String row, Context context) {
            if (row == null || row.isEmpty()) {
                return;
            }
            if (pendingCount.get() >= maxPending()) {
                // DB 长时间不可用：待写缓冲已满，丢弃新到数据（有上限才不会撑爆内存）
                if (++droppedRows == 1 || droppedRows % 10000 == 0) {
                    LOG.error("[L7VP-STREAM] 时序库不可用，待写缓冲已满({})，累计丢弃 {} 行",
                        maxPending(), droppedRows);
                }
                return;
            }
            pending.add(row);
            if (pendingCount.incrementAndGet() >= batchSize) {
                scheduler.execute(this::flush);
            }
        }

        private void flush() {
            long now = System.currentTimeMillis();
            if (now < nextRetryAt) {
                return; // 退避中：DB 不可用时不要每 batchMs 都重连+建索引
            }
            List<String> batch = new ArrayList<>(batchSize);
            String row;
            // 先消费上次失败的批次（DB 恢复后补写），再取新数据；跨批次顺序不敏感（event_time 是显式列）
            while (batch.size() < batchSize && (row = retry.poll()) != null) {
                batch.add(row);
            }
            while (batch.size() < batchSize && (row = pending.poll()) != null) {
                batch.add(row);
                pendingCount.decrementAndGet();
            }
            if (batch.isEmpty()) {
                return;
            }
            try {
                if (conn == null || conn.isClosed()) {
                    connect();
                }
                for (String r : batch) {
                    insertPs.setString(1, datasetId);
                    insertPs.setTimestamp(2, eventTime(r));
                    insertPs.setString(3, r);
                    insertPs.addBatch();
                }
                insertPs.executeBatch();
                conn.commit();
                failStreak = 0;
                nextRetryAt = 0L;
            } catch (Exception e) {
                try {
                    if (conn != null && !conn.getAutoCommit()) {
                        conn.rollback();
                    }
                } catch (Exception ignored) {
                }
                closeQuietly(); // 关闭连接，下次 flush 重建，容忍 DB 重启
                requeue(batch);
                failStreak++;
                nextRetryAt = now + backoffMs(failStreak);
                LOG.warn("[L7VP-STREAM] 时序库写入失败 rows={} 已退回重试缓冲 retryBuffer={} 第{}次 退避{}ms err={}",
                    batch.size(), retry.size(), failStreak, backoffMs(failStreak), e.toString());
            }
        }

        /** 指数退避：500ms 起，上限 10s（DB 长时间不可用时避免重连风暴） */
        private long backoffMs(int streak) {
            long ms = 500L << Math.min(Math.max(streak - 1, 0), 5);
            return Math.min(ms, 10_000L);
        }

        /** 重试缓冲上限：超过则丢弃该批并告警（宁可丢数据也不能撑爆内存） */
        private int maxPending() {
            return Math.max(batchSize * 20, 10_000);
        }

        private void requeue(List<String> batch) {
            int cap = maxPending();
            if (retry.size() + batch.size() > cap) {
                LOG.error("[L7VP-STREAM] 时序库持续不可用，重试缓冲已满({})，丢弃 {} 行", cap, batch.size());
                return;
            }
            retry.addAll(batch);
        }

        private void connect() throws SQLException {
            conn = DriverManager.getConnection(url, user, password);
            // 建表/建索引阶段用自动提交：DDL 本身幂等，且失败的 DDL 不能与 INSERT 批共用事务——
            // 否则 PG 会把事务置为 aborted，该连接上后续所有语句都被拒（current transaction is
            // aborted, commands ignored until end of transaction block），且重连后必然复现。
            conn.setAutoCommit(true);
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS " + table
                    + " (dataset_id text NOT NULL, event_time timestamptz NOT NULL, payload jsonb NOT NULL)");
                st.execute("SELECT create_hypertable('" + table + "', 'event_time', if_not_exists => TRUE)");
            } catch (SQLException e) {
                // 建表被并发/权限打断：连接已就绪，交给后续 INSERT 暴露真实问题
                LOG.warn("[L7VP-STREAM] 时序库建表/hypertable 失败: {}", e.getMessage());
            }
            // PG 9.5+ 支持 IF NOT EXISTS（此处 PG17）；必须幂等，否则重连时索引已存在会中断写入
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_ds_time ON " + table
                    + " (dataset_id, event_time DESC)");
            } catch (SQLException e) {
                // 并发建索引可能撞 duplicate key（pg_class），自动提交下不影响后续写入，仅记录
                LOG.warn("[L7VP-STREAM] 时序库索引创建失败(已忽略): {}", e.getMessage());
            }
            conn.setAutoCommit(false); // 仅 INSERT 批走事务，失败整体回滚后重试
            insertPs = conn.prepareStatement(
                "INSERT INTO " + table + " (dataset_id, event_time, payload) VALUES (?, ?, ?::jsonb)");
            LOG.info("[L7VP-STREAM] 时序库已连接 url={}, table={}", url, table);
        }

        /** event_time：优先 payload 配置字段 → 常见时间键 → 作业入库时间兜底 */
        private java.sql.Timestamp eventTime(String raw) {
            try {
                JsonNode root = mapper.readTree(raw);
                if (!timeField.isEmpty() && root.has(timeField)) {
                    java.sql.Timestamp ts = toTimestamp(root.get(timeField));
                    if (ts != null) {
                        return ts;
                    }
                }
                for (String key : TIME_KEY_CANDIDATES) {
                    if (root.has(key)) {
                        java.sql.Timestamp ts = toTimestamp(root.get(key));
                        if (ts != null) {
                            return ts;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return new java.sql.Timestamp(System.currentTimeMillis());
        }

        /** 支持 epoch 毫秒/秒（数值或字符串）、ISO-8601、yyyy-MM-dd HH:mm:ss[.SSS] */
        private java.sql.Timestamp toTimestamp(JsonNode v) {
            if (v.isNumber()) {
                return epochToTs(v.asLong());
            }
            String s = v.asText().trim();
            if (s.isEmpty()) {
                return null;
            }
            try {
                return epochToTs(Long.parseLong(s));
            } catch (NumberFormatException ignored) {
            }
            try {
                return java.sql.Timestamp.from(java.time.OffsetDateTime.parse(s).toInstant());
            } catch (Exception ignored) {
            }
            try {
                java.time.LocalDateTime ldt = java.time.LocalDateTime.parse(
                    s.replace('T', ' ').replace("Z", ""),
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]"));
                return java.sql.Timestamp.valueOf(ldt);
            } catch (Exception ignored) {
            }
            return null;
        }

        /** 兼容秒/毫秒：<1e12 视为秒时间戳 */
        private java.sql.Timestamp epochToTs(long x) {
            return new java.sql.Timestamp(x < 100_000_000_000L ? x * 1000L : x);
        }

        private void closeQuietly() {
            try {
                if (insertPs != null) {
                    insertPs.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (conn != null) {
                    conn.close();
                }
            } catch (Exception ignored) {
            }
            insertPs = null;
            conn = null;
        }

        @Override
        public void close() throws Exception {
            super.close();
            if (scheduler != null) {
                scheduler.shutdownNow();
            }
            try {
                if (conn != null && !conn.isClosed() && !conn.getAutoCommit()) {
                    conn.commit();
                }
            } catch (Exception ignored) {
            }
            closeQuietly();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> cfg = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0) {
                cfg.put(arg.substring(0, eq), arg.substring(eq + 1));
            }
        }
        return cfg;
    }

    private static String require(Map<String, String> cfg, String key) {
        String v = cfg.get(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("缺少作业参数: " + key);
        }
        return v;
    }
}
