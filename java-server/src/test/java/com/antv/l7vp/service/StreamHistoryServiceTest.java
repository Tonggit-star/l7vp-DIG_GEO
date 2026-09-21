package com.antv.l7vp.service;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域筛选（N8）的矩形判定。
 *
 * 只覆盖这个纯函数：查询主体要连时序库，属外部依赖，按本仓库惯例不进单测。
 */
class StreamHistoryServiceTest {

    /** 渤海：regions.json 里的口径 [117.0, 37.0, 122.5, 41.0] */
    private static final List<Double> BBox = Arrays.asList(117.0, 37.0, 122.5, 41.0);

    @Test
    void point_inside_bbox_is_matched() {
        assertTrue(StreamHistoryService.inBbox(119.7, 39.0, BBox));
    }

    @Test
    void point_on_boundary_is_matched() {
        // 贴边算命中：目标是「在这片海域里」，边界上的目标不该被漏掉
        assertTrue(StreamHistoryService.inBbox(117.0, 37.0, BBox));
        assertTrue(StreamHistoryService.inBbox(122.5, 41.0, BBox));
    }

    @Test
    void point_outside_bbox_is_not_matched() {
        assertFalse(StreamHistoryService.inBbox(116.9, 39.0, BBox), "西边出框");
        assertFalse(StreamHistoryService.inBbox(122.6, 39.0, BBox), "东边出框");
        assertFalse(StreamHistoryService.inBbox(119.7, 36.9, BBox), "南边出框");
        assertFalse(StreamHistoryService.inBbox(119.7, 41.1, BBox), "北边出框");
    }

    @Test
    void lng_outside_but_lat_inside_is_not_matched() {
        // 经纬度必须同时满足，不能只看一个维度
        assertFalse(StreamHistoryService.inBbox(121.5, 30.0, BBox));
    }
}
