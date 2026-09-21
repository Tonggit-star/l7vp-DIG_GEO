package com.antv.l7vp.config;

import com.antv.l7vp.websocket.StreamHandshakeInterceptor;
import com.antv.l7vp.websocket.StreamWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.apache.catalina.connector.Connector;

/**
 * WebSocket 配置
 *
 * - 端点：/ws/datasets/*  （前端订阅流式数据集实时数据）
 * - 端口：l7vp.ws.port 配置非空时，额外起一个 Tomcat 连接器监听该端口；
 *        留空则与 HTTP 同端口（server.port=3001）。
 *
 * 第二个连接器与主 Servlet 容器共享 ServletContext，故 /ws/datasets/* 在两个端口均可达。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Autowired
    private StreamWebSocketHandler streamWebSocketHandler;

    @Autowired
    private StreamHandshakeInterceptor streamHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Ant 风格 * 匹配单段 datasetId；datasetId 在拦截器里从 URI 解析
        registry.addHandler(streamWebSocketHandler, "/ws/datasets/*")
                .addInterceptors(streamHandshakeInterceptor)
                .setAllowedOrigins("*");
    }

    /**
     * 当 l7vp.ws.port 非空时，追加一个 Tomcat HTTP 连接器，使 WS 可独占端口。
     * 留空（默认）则 WS 复用 HTTP 端口，无需额外连接器。
     */
    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> wsPortCustomizer(
            StreamProperties props) {
        return factory -> {
            String portStr = props.getWs().getPort();
            if (portStr == null || portStr.trim().isEmpty()) {
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("l7vp.ws.port 必须是数字或留空，实际值: " + portStr, e);
            }
            Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
            connector.setPort(port);
            connector.setScheme("http");
            // 不强制 HTTPS；WS 自身可走 wss（由 nginx 终结 TLS）
            connector.setSecure(false);
            factory.addAdditionalTomcatConnectors(connector);
        };
    }
}
