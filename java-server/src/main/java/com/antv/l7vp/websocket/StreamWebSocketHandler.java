package com.antv.l7vp.websocket;

import com.antv.l7vp.service.StreamSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 流式数据集 WebSocket 处理器
 *
 * 前端连接 /ws/datasets/{datasetId} 后：
 * - 建立连接：注册到 StreamSessionManager，并立即下发当前环形缓冲快照（snapshot）
 * - 关闭连接：从会话管理器注销
 *
 * 前端→后端方向的文本消息不处理（数据单向推送，仅 Flink 经 push 端点注入）。
 */
@Component
public class StreamWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamWebSocketHandler.class);

    @Autowired
    private StreamSessionManager streamSessionManager;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String datasetId = getDatasetId(session);
        if (datasetId == null) {
            log.warn("[WS] 连接缺少 datasetId，关闭: {}", session.getId());
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        streamSessionManager.subscribe(datasetId, session);
        log.info("[WS] 订阅 datasetId={} session={}", datasetId, session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        streamSessionManager.unsubscribe(session);
        log.info("[WS] 连接关闭 session={} status={}", session.getId(), status);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 前端暂不向后端发消息；忽略（可作为未来心跳扩展点）
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("[WS] 传输错误 session={}: {}", session.getId(), exception.getMessage());
        streamSessionManager.unsubscribe(session);
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    private String getDatasetId(WebSocketSession session) {
        Object dsId = session.getAttributes().get("datasetId");
        return dsId != null ? dsId.toString() : null;
    }
}
