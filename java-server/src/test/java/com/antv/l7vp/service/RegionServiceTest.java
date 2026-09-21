package com.antv.l7vp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 区域字典（N2 / map.focus 的 region 模式）单测。数据直接读 src/main/resources/regions.json。
 */
class RegionServiceTest {

    private final RegionService regionService = new RegionService(new ObjectMapper());

    @Test
    void loads_full_dictionary() {
        assertEquals(12, regionService.search(null).size());
        assertEquals(12, regionService.listKeys().size());
    }

    @Test
    void search_by_name() {
        List<Map<String, Object>> hit = regionService.search("渤海");

        assertEquals(1, hit.size());
        assertEquals("bohai", hit.get(0).get("key"));
        assertEquals("渤海", hit.get(0).get("name"));
    }

    @Test
    void search_by_alias_is_case_insensitive() {
        List<Map<String, Object>> hit = regionService.search("yellow sea");

        assertEquals(1, hit.size());
        assertEquals("huanghai", hit.get(0).get("key"));
    }

    @Test
    void search_tolerates_modifier_suffix() {
        // 容忍「XX区域」「XX方向」「XX附近」这类问法（反向包含）
        List<Map<String, Object>> hit = regionService.search("渤海区域");

        assertEquals(1, hit.size());
        assertEquals("bohai", hit.get(0).get("key"));
    }

    @Test
    void search_unknown_returns_empty() {
        assertTrue(regionService.search("火星湾").isEmpty());
    }

    @Test
    void find_by_key() {
        assertNotNull(regionService.findByKey("bohai"));
        assertEquals("南海", regionService.findByKey("nanhai").get("name"));
        assertNull(regionService.findByKey("no_such_region"));
        assertNull(regionService.findByKey(null));
    }

    @Test
    void region_carries_usable_bbox_and_zoom() {
        Map<String, Object> region = regionService.findByKey("bohai");

        List<?> bbox = (List<?>) region.get("bbox");
        assertEquals(4, bbox.size());
        assertTrue(((Number) bbox.get(0)).doubleValue() < ((Number) bbox.get(2)).doubleValue());
        assertTrue(((Number) bbox.get(1)).doubleValue() < ((Number) bbox.get(3)).doubleValue());
        assertNotNull(region.get("zoom"));
    }

    @Test
    void to_bounds_payload_is_what_the_page_executor_consumes() {
        Map<String, Object> payload = regionService.toBoundsPayload(regionService.findByKey("bohai"));

        // 页面执行器只认 point/bounds（附录 C 决策 16）
        assertEquals("bounds", payload.get("mode"));
        assertNotNull(payload.get("bbox"));
        assertEquals("bohai", payload.get("regionKey"));
    }
}
