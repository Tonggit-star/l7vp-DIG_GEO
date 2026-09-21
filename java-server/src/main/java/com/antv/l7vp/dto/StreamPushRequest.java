package com.antv.l7vp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Flink 推送流式数据请求体
 *
 * POST /api/projects/{projectId}/datasets/{datasetId}/stream/push
 */
@Data
public class StreamPushRequest {
    /**
     * 本次推送的数据行，每行为 列名→值 的对象。
     * 字段应与数据集列定义匹配（Flink 侧按列名产出）。
     */
    private List<Map<String, Object>> rows;

    /**
     * 操作类型：append=追加（默认），replace=清空缓冲后写入。
     */
    private String op;
}
