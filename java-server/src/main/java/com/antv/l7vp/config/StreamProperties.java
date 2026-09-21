package com.antv.l7vp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 流式数据集 / WebSocket 相关配置（application.properties 中 l7vp.ws / l7vp.stream）
 *
 * - l7vp.ws.port：WebSocket 端口；留空则与 HTTP 同端口（server.port）。
 * - l7vp.stream.max-window：每个流式数据集内存环形缓冲最大行数。
 * - l7vp.stream.push-token：Flink 推送鉴权令牌；留空则不强制鉴权。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "l7vp")
public class StreamProperties {

    private Ws ws = new Ws();

    private Stream stream = new Stream();

    @Data
    public static class Ws {
        /** WebSocket 端口；空字符串表示与 HTTP 同端口 */
        private String port = "";
    }

    @Data
    public static class Stream {
        /** 每个流式数据集内存环形缓冲最大行数 */
        private int maxWindow = 1000;

        /** Flink 推送鉴权令牌；空表示不强制鉴权 */
        private String pushToken = "";
    }
}
