package com.antv.l7vp.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.Map;

/**
 * WebSocket 握手拦截器
 *
 * - 从 URI 路径解析 datasetId 并放入握手属性（/ws/datasets/{datasetId}）
 * - 尝试读取当前登录 Session（同源请求会携带 Cookie，与 HTTP 接口一致）；
 *   未登录不强制拒绝（与 /api/projects 等开放接口行为一致），仅记录日志。
 *   如需强制鉴权，可在此处返回 false 拒绝握手。
 */
@Component
public class StreamHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(StreamHandshakeInterceptor.class);

    private static final String PATH_PREFIX = "/ws/datasets/";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String datasetId = extractDatasetId(request);
        if (datasetId == null || datasetId.isEmpty()) {
            log.warn("[WS_HANDSHAKE] 无法从路径解析 datasetId: {}", request.getURI().getPath());
            return false;
        }
        attributes.put("datasetId", datasetId);

        // 尝试读取登录态（可选，不强制）
        if (request instanceof ServletServerHttpRequest) {
            HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
            HttpSession session = servletRequest.getSession(false);
            if (session != null) {
                attributes.put("httpSession", session);
            }
        }
        log.info("[WS_HANDSHAKE] datasetId={} 握手通过", datasetId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** 从 /ws/datasets/{datasetId}[...] 路径中解析出 datasetId */
    private String extractDatasetId(ServerHttpRequest request) {
        String path = request.getURI().getPath();
        if (path == null) return null;
        int idx = path.indexOf(PATH_PREFIX);
        if (idx < 0) return null;
        String tail = path.substring(idx + PATH_PREFIX.length());
        // 取第一个路径段，忽略可能的查询/尾部斜杠
        int slash = tail.indexOf('/');
        return slash >= 0 ? tail.substring(0, slash) : tail;
    }
}
