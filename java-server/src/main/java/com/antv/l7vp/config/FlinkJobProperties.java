package com.antv.l7vp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Flink 流式作业提交相关配置（application.properties 中 flink.* 前缀）。
 *
 * 部署时优先用环境变量覆盖（改环境变量即可，不用改代码）：
 *   FLINK_AUTO_START            保存项目时自动为含 kafka 的流式数据集提交作业，默认 false
 *   FLINK_CLUSTER_URL            Flink 集群 REST 地址，默认 http://localhost:8081
 *   FLINK_JOB_ENTRY_CLASS        作业入口类（默认内嵌 jar 的主类）
 *   FLINK_JOB_PARALLELISM        作业并行度，默认 1
 *   FLINK_JOB_BATCH_SIZE         单批回推行数，默认 200
 *   FLINK_JOB_BATCH_MS           回推攒批窗口毫秒，默认 500
 *   FLINK_JOB_JAR_FILE_PATH      外部作业 jar（空=使用内嵌 flink-job/l7vp-stream-job.jar）
 *   FLINK_PUSH_BASE_URL          集群侧可达的本服务地址（生产务必显式配置）
 *   FLINK_PUSH_TOKEN             回推鉴权令牌（沿用 L7VP_STREAM_PUSH_TOKEN 语义）
 *   FLINK_KAFKA_BOOTSTRAP_SERVERS Kafka 兜底地址（可按数据集 metadata.kafka 覆盖）
 *   FLINK_KAFKA_GROUP_ID_PREFIX  作业 group.id 前缀
 *   FLINK_KAFKA_AUTO_OFFSET_RESET 作业消费起始位置 earliest|latest，默认 latest（生产实时）
 *                                 —— 测试/历史回放想先灌存量时设 earliest
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "flink")
public class FlinkJobProperties {

    private Cluster cluster = new Cluster();
    private Job job = new Job();
    private Push push = new Push();
    private Kafka kafka = new Kafka();
    private Tsdb tsdb = new Tsdb();

    /** 保存项目时是否自动为含 kafka 配置的流式数据集提交作业（幂等）。默认关，集群就绪后设 FLINK_AUTO_START=true */
    private boolean autoStart = false;

    @Data
    public static class Cluster {
        /** Flink REST 地址（含协议与端口），如 http://192.168.1.100:8081 */
        private String url = "http://localhost:8081";
    }

    @Data
    public static class Job {
        /** 作业入口类（flink-job 主类） */
        private String entryClass = "com.antv.l7vp.flink.StreamDatasetJob";
        private int parallelism = 1;
        /** 单批回推行数 */
        private int batchSize = 200;
        /** 攒批窗口（毫秒） */
        private long batchMs = 500L;
        /** 外部作业 jar 绝对路径；空=用 classpath 内嵌资源 flink-job/l7vp-stream-job.jar */
        private String jarFilePath = "";
    }

    @Data
    public static class Push {
        /** 集群侧可访问的本服务基地址（不带尾部 /），回推地址 = baseUrl + /api/... */
        private String baseUrl = "http://localhost:3001";
        /** 回推鉴权令牌；空=不加 X-Stream-Token */
        private String token = "";
    }

    @Data
    public static class Kafka {
        /** 兜底 bootstrap servers；优先取数据集 metadata.kafka.bootstrapServers */
        private String bootstrapServers = "";
        private String groupIdPrefix = "l7vp-stream";
        /**
         * 作业 Kafka 消费起始位置：earliest|latest，默认 latest（生产实时只读新消息）。
         * earliest 用于测试/历史回放（作业每次重启从头重读）。单数据集可用 overrides.autoOffsetReset 覆盖。
         */
        private String autoOffsetReset = "latest";
    }

    @Data
    public static class Tsdb {
        /** 是否让 Flink 作业把消息同时写入时序库（TimescaleDB/PostgreSQL） */
        private boolean enabled = false;
        /** JDBC 地址，如 jdbc:postgresql://192.168.10.1:5432/maritime */
        private String url = "";
        private String user = "";
        private String password = "";
        /** 落库表（作业启动幂等自建 jsonb 超表） */
        private String table = "stream_events";
        /** payload 中的事件时间键(epoch毫秒/秒 或 ISO 或 yyyy-MM-dd HH:mm:ss)；空=按常见键探测，再兜底入库时间 */
        private String timeField = "";
        /**
         * 前端「历史轨迹」单次查询的最大返回行数（读路径，不影响作业写入）。
         * 请求可用 limit 覆盖，但会被钳制在 [1, historyMaxRowsLimit]。
         */
        private int historyMaxRows = 20000;
    }
}
