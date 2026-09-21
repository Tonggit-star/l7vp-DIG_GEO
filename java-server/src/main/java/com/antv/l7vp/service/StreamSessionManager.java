package com.antv.l7vp.service;

import com.antv.l7vp.config.StreamProperties;
import com.antv.l7vp.model.Dataset;
import com.antv.l7vp.repository.DatasetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

/**
 * 流式数据集会话与缓冲管理器
 *
 * 每个 datasetId 对应一个 StreamTopic：
 * - subscribers：订阅该数据集的前端 WebSocket 会话集合
 * - buffer：内存环形缓冲（最近 maxWindow 行），新订阅者连接时下发快照
 *
 * 数据不落库（重启无历史，符合流式语义）。Flink 通过 push 端点注入数据，
 * 这里负责裁剪缓冲并广播给所有订阅者。
 */
@Component
public class StreamSessionManager {

    private static final Logger log = LoggerFactory.getLogger(StreamSessionManager.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StreamProperties streamProperties;

    @Autowired
    private DatasetRepository datasetRepository;

    private final ConcurrentMap<String, StreamTopic> topics = new ConcurrentHashMap<>();

    private static class StreamTopic {
        final ConcurrentMap<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
        final ConcurrentLinkedDeque<Map<String, Object>> buffer = new ConcurrentLinkedDeque<>();
        volatile int maxWindow;
    }

    /** 前端订阅：注册会话并立即下发当前缓冲快照 */
    public void subscribe(String datasetId, WebSocketSession session) {
        StreamTopic topic = getOrCreate(datasetId);
        topic.sessions.put(session.getId(), session);
        try {
            sendFrame(session, "snapshot", datasetId, "replace", new ArrayList<>(topic.buffer));
        } catch (Exception e) {
            log.warn("[STREAM] 下发 snapshot 失败 datasetId={} session={}: {}", datasetId, session.getId(), e.getMessage());
        }
    }

    /** 前端断开：注销会话 */
    public void unsubscribe(WebSocketSession session) {
        String datasetId = (String) session.getAttributes().get("datasetId");
        if (datasetId == null) return;
        StreamTopic topic = topics.get(datasetId);
        if (topic != null) {
            topic.sessions.remove(session.getId());
        }
    }

    /** Flink 注入数据：裁剪缓冲并广播 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int push(String datasetId, List<Map<String, Object>> rows, String op) {
        StreamTopic topic = getOrCreate(datasetId);
        List<Map<String, Object>> safeRows = rows == null ? new ArrayList<>() : rows;
        synchronized (topic) {
            if ("replace".equalsIgnoreCase(op)) {
                topic.buffer.clear();
            }
            for (Map<String, Object> row : safeRows) {
                topic.buffer.addLast(row);
            }
            while (topic.buffer.size() > topic.maxWindow) {
                topic.buffer.pollFirst();
            }
        }
        // 广播给所有订阅者
        if (!topic.sessions.isEmpty()) {
            String frame = buildFrame("data", datasetId, op == null ? "append" : op, safeRows);
            for (WebSocketSession session : topic.sessions.values()) {
                sendText(session, frame, datasetId);
            }
        }
        log.info("[STREAM] push datasetId={} op={} rows={} subscribers={} buffered={}",
                datasetId, op, safeRows.size(), topic.sessions.size(), topic.buffer.size());
        return safeRows.size();
    }

    /**
     * 清空某数据集的环形缓冲，并向所有在线订阅者广播一帧空 replace，
     * 让已打开的前端页面立即清空图层（配合 stream/reload 完成「重新加载」）。
     */
    public int clear(String datasetId) {
        StreamTopic topic = topics.get(datasetId);
        if (topic == null) {
            return 0;
        }
        synchronized (topic) {
            topic.buffer.clear();
        }
        if (!topic.sessions.isEmpty()) {
            String frame = buildFrame("data", datasetId, "replace", new ArrayList<>());
            for (WebSocketSession session : topic.sessions.values()) {
                sendText(session, frame, datasetId);
            }
        }
        log.info("[STREAM] clear datasetId={} subscribers={}", datasetId, topic.sessions.size());
        return topic.sessions.size();
    }

    public int getSubscriberCount(String datasetId) {
        StreamTopic topic = topics.get(datasetId);
        return topic == null ? 0 : topic.sessions.size();
    }

    private StreamTopic getOrCreate(String datasetId) {
        return topics.computeIfAbsent(datasetId, id -> {
            StreamTopic t = new StreamTopic();
            t.maxWindow = resolveMaxWindow(id);
            return t;
        });
    }

    /** 解析该数据集的 maxWindow：优先读 DATASETS.METADATA.maxWindow，否则用全局默认 */
    @SuppressWarnings("unchecked")
    private int resolveMaxWindow(String datasetId) {
        int fallback = streamProperties.getStream().getMaxWindow();
        try {
            Dataset ds = datasetRepository.findById(datasetId);
            if (ds == null || ds.getMetadata() == null) {
                return fallback;
            }
            Map<String, Object> meta = objectMapper.readValue(ds.getMetadata(), Map.class);
            Object mw = meta.get("maxWindow");
            if (mw instanceof Number) {
                return ((Number) mw).intValue();
            }
            return fallback;
        } catch (Exception e) {
            log.debug("[STREAM] 解析 maxWindow 失败 datasetId={}, 用默认 {}: {}", datasetId, fallback, e.getMessage());
            return fallback;
        }
    }

    private String buildFrame(String type, String datasetId, String op, List<Map<String, Object>> rows) {
        try {
            Map<String, Object> frame = new java.util.LinkedHashMap<>();
            frame.put("type", type);
            frame.put("datasetId", datasetId);
            frame.put("op", op);
            frame.put("rows", rows);
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.error("[STREAM] 序列化帧失败: {}", e.getMessage());
            return "{}";
        }
    }

    private void sendFrame(WebSocketSession session, String type, String datasetId, String op, List<Map<String, Object>> rows)
            throws IOException {
        String payload = buildFrame(type, datasetId, op, rows);
        sendText(session, payload, datasetId);
    }

    private void sendText(WebSocketSession session, String payload, String datasetId) {
        if (session == null || !session.isOpen()) return;
        try {
            session.sendMessage(new TextMessage(payload));
        } catch (IOException e) {
            log.warn("[STREAM] 发送失败 datasetId={} session={}: {}", datasetId, session.getId(), e.getMessage());
            topicFor(datasetId, session);
        }
    }

    private void topicFor(String datasetId, WebSocketSession session) {
        StreamTopic topic = topics.get(datasetId);
        if (topic != null) topic.sessions.remove(session.getId());
    }
}
