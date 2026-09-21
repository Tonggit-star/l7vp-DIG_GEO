package com.antv.l7vp.controller;

import com.antv.l7vp.config.L7vpAuthProperties;
import com.antv.l7vp.model.Layer;
import com.antv.l7vp.model.Project;
import com.antv.l7vp.model.DatasetColumn;
import com.antv.l7vp.repository.DatasetColumnRepository;
import com.antv.l7vp.repository.LayerRepository;
import com.antv.l7vp.repository.ProjectRepository;
import com.antv.l7vp.service.AgentCommandService;
import com.antv.l7vp.service.RegionService;
import com.antv.l7vp.service.StreamHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 指令白名单校验与参数校验（§9.4 / 附录 C 决策 15/16/17）：非法输入一律 400 + {"error"}。
 */
@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AgentCommandService agentCommandService;

    @MockBean
    private RegionService regionService;

    @MockBean
    private StreamHistoryService streamHistoryService;

    @MockBean
    private LayerRepository layerRepository;

    @MockBean
    private ProjectRepository projectRepository;

    /** layer.update 的 parser 字段校验要查数据集列定义（v1.4） */
    @MockBean
    private DatasetColumnRepository datasetColumnRepository;

    /** WebConfig 实现 WebMvcConfigurer，会被切片带进来；它的 localUserFilter 需要这个配置 bean */
    @MockBean
    private L7vpAuthProperties l7vpAuthProperties;

    private Layer layer(String id, String name, String projectId) {
        Layer layer = new Layer();
        layer.setLayerId(id);
        layer.setLayerName(name);
        layer.setProjectId(projectId);
        return layer;
    }

    private Project project(String id, String name) {
        Project project = new Project();
        project.setProjectId(id);
        project.setProjectName(name);
        project.setUpdateTime("2026-09-17 10:00:00");
        return project;
    }

    /** regions.json 里渤海那条 */
    private Map<String, Object> bohai() {
        Map<String, Object> region = new LinkedHashMap<>();
        region.put("key", "bohai");
        region.put("name", "渤海");
        region.put("bbox", Arrays.asList(117.0, 37.0, 122.5, 41.0));
        return region;
    }

    private StreamHistoryService.RegionTargetResult regionResult(int rowCount, int scanned) {
        StreamHistoryService.RegionTargetResult result = mock(StreamHistoryService.RegionTargetResult.class);
        when(result.getRows()).thenReturn(new ArrayList<>());
        when(result.getRowCount()).thenReturn(rowCount);
        when(result.getScanned()).thenReturn(scanned);
        when(result.getField()).thenReturn("mmsi");
        return result;
    }

    /** 入队成功的桩：EnqueueResult 的构造器是包内可见的，只能 mock */
    private void stubEnqueue() {
        AgentCommandService.EnqueueResult enqueued = mock(AgentCommandService.EnqueueResult.class);
        AgentCommandService.Command command = mock(AgentCommandService.Command.class);
        when(enqueued.getCommand()).thenReturn(command);
        when(command.getCmdId()).thenReturn("c_1");
        when(command.getSeq()).thenReturn(1L);
        when(agentCommandService.enqueue(anyString(), anyString(), any(Map.class))).thenReturn(enqueued);
    }

    /** 取出真正入队的归一化载荷——校验默认值（如 keepTiles）只能看这个 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedPayload(String expectedType) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(agentCommandService).enqueue(anyString(), eq(expectedType), captor.capture());
        return captor.getValue();
    }

    private String body(String type, Map<String, Object> payload) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("payload", payload);
        return objectMapper.writeValueAsString(body);
    }

    @Test
    void unknown_command_type_is_rejected() throws Exception {
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.explode", new LinkedHashMap<>())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("未知指令类型")))
                // 报错里要把支持的类型列全，新增指令别漏进这句
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer.bringToFront")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer.sendToBack")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("dataset.create")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer.update")));
    }

    @Test
    void layer_visibility_requires_layer_reference() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layerId 或 layerName")));
    }

    @Test
    void layer_visibility_requires_boolean_visible() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("visible", "yes");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("布尔字段 visible")));
    }

    @Test
    void layer_id_of_another_project_is_rejected() throws Exception {
        when(layerRepository.findById("layer_x")).thenReturn(layer("layer_x", "别人的图层", "p2"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_x");
        payload.put("visible", true);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不属于该项目")));
    }

    @Test
    void unknown_layer_name_lists_candidates() throws Exception {
        when(layerRepository.findByProjectId("p1"))
                .thenReturn(Arrays.asList(layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerName", "船舶");
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("船舶实时")));
    }

    @Test
    void duplicate_layer_name_is_rejected_with_ids() throws Exception {
        when(layerRepository.findByProjectId("p1"))
                .thenReturn(Arrays.asList(layer("layer_1", "船舶实时", "p1"), layer("layer_2", "船舶实时", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerName", "船舶实时");
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_1")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_2")));
    }

    @Test
    void focus_point_without_lat_is_rejected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "point");
        payload.put("lng", 121.47);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.focus", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("纬度 lat")));
    }

    @Test
    void focus_point_out_of_range_is_rejected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "point");
        payload.put("lng", 200.0);
        payload.put("lat", 31.23);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.focus", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("经度 lng")));
    }

    @Test
    void focus_bounds_with_inverted_bbox_is_rejected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "bounds");
        payload.put("bbox", Arrays.asList(122.5, 41.0, 117.0, 37.0));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.focus", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("minLng < maxLng")));
    }

    @Test
    void focus_bounds_with_wrong_arity_is_rejected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "bounds");
        payload.put("bbox", Arrays.asList(117.0, 37.0, 122.5));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.focus", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("4 个元素")));
    }

    @Test
    void focus_unknown_region_lists_available_keys() throws Exception {
        when(regionService.findByKey("atlantis")).thenReturn(null);
        when(regionService.listKeys()).thenReturn(Arrays.asList("bohai", "nanhai"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "region");
        payload.put("regionKey", "atlantis");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("map.focus", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bohai")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bounds 模式")));
    }

    @Test
    void target_select_requires_target_object_and_coordinates() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", "245272000");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("target.select", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("需要 target 对象")));
    }

    @Test
    void targets_not_found_dataset_returns_404() throws Exception {
        when(streamHistoryService.queryTargets(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StreamHistoryService.DatasetNotFound("数据集不存在: ds_x"));

        mockMvc.perform(get("/api/projects/p1/agent/targets?datasetId=ds_x&key=245272000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("数据集不存在")));
    }

    @Test
    void targets_without_tsdb_returns_503() throws Exception {
        when(streamHistoryService.queryTargets(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenThrow(new StreamHistoryService.TsdbUnavailable("未配置时序库"));

        mockMvc.perform(get("/api/projects/p1/agent/targets?datasetId=ds_x&key=245272000"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void regions_not_found_returns_200_with_empty_list() throws Exception {
        when(regionService.search("火星湾")).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/projects/p1/agent/regions?q=火星湾"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.regions").isEmpty());
    }

    @Test
    void command_result_for_unknown_cmd_returns_404() throws Exception {
        when(agentCommandService.find("p1", "c_nope")).thenReturn(null);

        mockMvc.perform(get("/api/projects/p1/agent/commands/c_nope/result"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("未知指令")));
    }

    @Test
    void reporting_result_for_unknown_cmd_returns_404() throws Exception {
        when(agentCommandService.reportResult(anyString(), anyString(), anyBoolean(), any(), any()))
                .thenReturn(false);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);

        mockMvc.perform(post("/api/projects/p1/agent/commands/c_nope/result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    // ==================== N7：查项目列表（v1.2） ====================

    @Test
    void list_projects_returns_lightweight_rows() throws Exception {
        when(projectRepository.searchByName("船舶", 50)).thenReturn(Arrays.asList(project("p1", "船舶监控")));

        mockMvc.perform(get("/api/agent/projects").param("q", "船舶"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(1))
                .andExpect(jsonPath("$.projects[0].projectId").value("p1"))
                .andExpect(jsonPath("$.projects[0].projectName").value("船舶监控"))
                .andExpect(jsonPath("$.projects[0].updateTime").value("2026-09-17 10:00:00"));
    }

    @Test
    void list_projects_clamps_limit_and_tolerates_missing_query() throws Exception {
        // limit 超过硬顶 → 钳到 200；不带 q → 关键词为 null（等价于按更新时间取前 N 个）
        when(projectRepository.searchByName(null, 200)).thenReturn(new ArrayList<>());

        mockMvc.perform(get("/api/agent/projects").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(0))
                .andExpect(jsonPath("$.projects").isEmpty());
    }

    // ==================== N8：区域内目标（v1.2） ====================

    @Test
    void region_targets_resolve_dictionary_region_by_key() throws Exception {
        // 结果对象要先造好再进 when(...)：mock 的构建本身会开一次 stubbing，嵌套会报 UnfinishedStubbing
        StreamHistoryService.RegionTargetResult result = regionResult(3, 120);
        when(regionService.findByKey("bohai")).thenReturn(bohai());
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(result);

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "bohai"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.region").value("bohai"))
                .andExpect(jsonPath("$.filter.regionKey").value("bohai"))
                .andExpect(jsonPath("$.filter.regionName").value("渤海"))
                .andExpect(jsonPath("$.filter.bbox[0]").value(117.0))
                .andExpect(jsonPath("$.filter.bbox[3]").value(41.0))
                .andExpect(jsonPath("$.field").value("mmsi"))
                .andExpect(jsonPath("$.rowCount").value(3))
                .andExpect(jsonPath("$.scanned").value(120))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void region_targets_resolve_chinese_name_and_apply_defaults() throws Exception {
        StreamHistoryService.RegionTargetResult result = regionResult(0, 0);
        when(regionService.findByKey("渤海")).thenReturn(null);
        when(regionService.search("渤海")).thenReturn(Arrays.asList(bohai()));
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(result);

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "渤海"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filter.regionKey").value("bohai"));

        // 默认 limit=200、扫描 20000 行、不只看个数
        verify(streamHistoryService).queryTargetsInRegion(eq("p1"), eq("ds1"), eq("bohai"),
                eq(Arrays.asList(117.0, 37.0, 122.5, 41.0)), isNull(), eq(200), eq(false), eq(20000));
    }

    @Test
    void region_targets_ambiguous_name_is_rejected_with_candidates() throws Exception {
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("key", "taiwan_strait");
        second.put("name", "台湾海峡");
        when(regionService.findByKey("海峡")).thenReturn(null);
        when(regionService.search("海峡")).thenReturn(Arrays.asList(bohai(), second));

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "海峡"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bohai")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("taiwan_strait")));
    }

    @Test
    void region_targets_unknown_region_lists_available_keys() throws Exception {
        when(regionService.findByKey("火星湾")).thenReturn(null);
        when(regionService.search("火星湾")).thenReturn(new ArrayList<>());
        when(regionService.listKeys()).thenReturn(Arrays.asList("bohai", "nanhai"));

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "火星湾"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bohai")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("bbox")));
    }

    @Test
    void region_targets_require_region_or_bbox() throws Exception {
        // 两者都不给
        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region").param("datasetId", "ds1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("二选一")));

        // 两者都给 → 同样拒绝，不猜调用方想要哪个
        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "bohai")
                        .param("bbox", "117,37,122.5,41"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("二选一")));
    }

    @Test
    void region_targets_accept_explicit_bbox() throws Exception {
        StreamHistoryService.RegionTargetResult result = regionResult(1, 5);
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(result);

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("bbox", "117,37,122.5,41"))
                .andExpect(status().isOk())
                // 手给 bbox 时 regionKey/regionName 为 null（字段仍在，形状不随模式变）
                .andExpect(jsonPath("$.filter.regionKey").isEmpty())
                .andExpect(jsonPath("$.filter.regionName").isEmpty())
                .andExpect(jsonPath("$.filter.bbox[1]").value(37.0))
                .andExpect(jsonPath("$.rowCount").value(1));

        // 手给 bbox 时不查字典，regionKey 为 null（每行的 region 字段也随之是 null）
        verify(streamHistoryService).queryTargetsInRegion(eq("p1"), eq("ds1"), isNull(),
                eq(Arrays.asList(117.0, 37.0, 122.5, 41.0)), isNull(), eq(200), eq(false), eq(20000));
    }

    @Test
    void region_targets_bbox_with_wrong_arity_is_rejected() throws Exception {
        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("bbox", "117,37,122.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("4 个逗号分隔")));
    }

    @Test
    void region_targets_count_only_is_forwarded() throws Exception {
        StreamHistoryService.RegionTargetResult result = regionResult(42, 900);
        when(regionService.findByKey("bohai")).thenReturn(bohai());
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenReturn(result);

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "bohai")
                        .param("countOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(42))
                .andExpect(jsonPath("$.rows").isEmpty());
    }

    @Test
    void region_targets_dataset_not_found_returns_404() throws Exception {
        when(regionService.findByKey("bohai")).thenReturn(bohai());
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenThrow(new StreamHistoryService.DatasetNotFound("数据集不存在: ds_x"));

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds_x")
                        .param("region", "bohai"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("数据集不存在")));
    }

    @Test
    void region_targets_without_tsdb_returns_503() throws Exception {
        when(regionService.findByKey("bohai")).thenReturn(bohai());
        when(streamHistoryService.queryTargetsInRegion(anyString(), anyString(), any(), any(), any(), any(),
                anyBoolean(), any())).thenThrow(new StreamHistoryService.TsdbUnavailable("未配置时序库"));

        mockMvc.perform(get("/api/projects/p1/agent/targets/in-region")
                        .param("datasetId", "ds1")
                        .param("region", "bohai"))
                .andExpect(status().isServiceUnavailable());
    }

    // ==================== 批量显隐与 layer.isolate（v1.2） ====================

    @Test
    void batch_visibility_defaults_keep_tiles_true() throws Exception {
        stubEnqueue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "all");
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cmdId").value("c_1"));

        Map<String, Object> normalized = capturedPayload("layer.visibility");
        assertEquals("all", normalized.get("scope"));
        assertEquals(Boolean.FALSE, normalized.get("visible"));
        // 不带 keepTiles → 默认 true（瓦片一律不动）
        assertEquals(Boolean.TRUE, normalized.get("keepTiles"));
    }

    @Test
    void batch_visibility_accepts_explicit_keep_tiles_false() throws Exception {
        stubEnqueue();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "all");
        payload.put("visible", true);
        payload.put("keepTiles", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isOk());

        assertEquals(Boolean.FALSE, capturedPayload("layer.visibility").get("keepTiles"));
    }

    @Test
    void batch_visibility_rejects_scope_with_explicit_layer() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "all");
        payload.put("layerId", "layer_1");
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不能同时给")));
    }

    @Test
    void batch_visibility_rejects_unknown_scope() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("scope", "dataset");
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("仅支持 all")));
    }

    @Test
    void isolate_requires_layer_reference() throws Exception {
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.isolate", new LinkedHashMap<>())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layerIds 或 layerNames")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolate_resolves_names_and_defaults_keep_tiles() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));
        stubEnqueue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerNames", Arrays.asList("船舶实时"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.isolate", payload)))
                .andExpect(status().isOk());

        Map<String, Object> normalized = capturedPayload("layer.isolate");
        assertEquals(Boolean.TRUE, normalized.get("keepTiles"));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) normalized.get("layers");
        assertEquals(1, layers.size());
        assertEquals("layer_1", layers.get(0).get("layerId"));
        assertEquals("船舶实时", layers.get(0).get("layerName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void isolate_dedupes_id_and_name_of_same_layer() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));
        stubEnqueue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerIds", Arrays.asList("layer_1"));
        payload.put("layerNames", Arrays.asList("船舶实时"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.isolate", payload)))
                .andExpect(status().isOk());

        List<Map<String, Object>> layers = (List<Map<String, Object>>) capturedPayload("layer.isolate").get("layers");
        assertEquals(1, layers.size(), "同一个图层被 id 和 name 各点一次只应留一份");
    }

    @Test
    void isolate_unknown_name_lists_project_layers() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerNames", Arrays.asList("不存在的图层"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.isolate", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("历史轨迹")));
    }

    @Test
    void isolate_duplicate_name_is_rejected_with_ids() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "船舶实时", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerNames", Arrays.asList("船舶实时"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.isolate", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_1")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_2")));
    }

    // ---- 层级：layer.bringToFront / layer.sendToBack（v1.3，只提供提到最上/压到最下两个动作）----

    @Test
    void bring_to_front_resolves_name_and_keeps_only_layer_ref() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));
        stubEnqueue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerName", "船舶实时");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.bringToFront", payload)))
                .andExpect(status().isOk());

        // 后端只负责把「名字」钉成 id；zIndex 由页面侧算（层清单与瓦片层只有页面知道）
        Map<String, Object> normalized = capturedPayload("layer.bringToFront");
        assertEquals("layer_1", normalized.get("layerId"));
        assertEquals("船舶实时", normalized.get("layerName"));
        assertFalse(normalized.containsKey("zIndex"), "zIndex 不应由后端下发");
    }

    @Test
    void send_to_back_resolves_id_and_fills_name() throws Exception {
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));
        stubEnqueue();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.sendToBack", payload)))
                .andExpect(status().isOk());

        Map<String, Object> normalized = capturedPayload("layer.sendToBack");
        assertEquals("layer_1", normalized.get("layerId"));
        assertEquals("船舶实时", normalized.get("layerName"), "回执要好读，名字由后端补齐");
    }

    @Test
    void layer_order_requires_layer_reference() throws Exception {
        for (String type : Arrays.asList("layer.bringToFront", "layer.sendToBack")) {
            mockMvc.perform(post("/api/projects/p1/agent/commands")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(type, new LinkedHashMap<>())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layerId 或 layerName")));
        }
    }

    @Test
    void layer_order_rejects_layer_of_another_project() throws Exception {
        when(layerRepository.findById("layer_x")).thenReturn(layer("layer_x", "别人的图层", "p2"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_x");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.bringToFront", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不属于该项目")));
    }

    @Test
    void layer_order_unknown_name_lists_project_layers() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "历史轨迹", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerName", "不存在的图层");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.sendToBack", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("未找到名为")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("历史轨迹")));
    }

    @Test
    void layer_order_duplicate_name_is_rejected_with_ids() throws Exception {
        when(layerRepository.findByProjectId("p1")).thenReturn(Arrays.asList(
                layer("layer_1", "船舶实时", "p1"), layer("layer_2", "船舶实时", "p1")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerName", "船舶实时");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.bringToFront", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("同名图层")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_1")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("layer_2")));
    }

    @Test
    void rate_limited_returns_429() throws Exception {
        when(agentCommandService.enqueue(anyString(), anyString(), any(Map.class)))
                .thenThrow(new AgentCommandService.RateLimited("下发过于频繁"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("visible", false);
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.visibility", payload)))
                .andExpect(status().isTooManyRequests());
    }

    // ==================== v1.4：dataset.create ====================

    /** 两步建图层第一步：列/行原样入队，类型走别名映射，autoCreateLayer 默认 true */
    @Test
    void dataset_create_happy_path_normalizes_columns_and_rows() throws Exception {
        stubEnqueue();
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "测试船舶");
        payload.put("columns", Arrays.asList(
                column("mmsi", "string", null),
                column("lng", "double", "经度"),
                column("lat", "float", null)));
        payload.put("rows", Arrays.asList(row("mmsi", "413000001", "lng", 120.1, "lat", 36.2)));
        payload.put("layerName", "测试船舶图层");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isOk())
                // 响应要告诉智能体哪个页面能执行它：config 类指令只有 Builder 页的执行器会取
                .andExpect(jsonPath("$.capability").value("config"));

        Map<String, Object> captured = capturedPayload("dataset.create");
        assertEquals("测试船舶", captured.get("datasetName"));
        assertEquals("测试船舶图层", captured.get("layerName"));
        assertEquals(Boolean.TRUE, captured.get("autoCreateLayer"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) captured.get("columns");
        assertEquals(3, columns.size());
        assertEquals("mmsi", columns.get(0).get("name"));
        // double/float 归一到 number，保留 business 语义但不泄漏驱动类型名
        assertEquals("string", columns.get(0).get("type"));
        assertEquals("number", columns.get(1).get("type"));
        assertEquals("number", columns.get(2).get("type"));
        assertEquals("经度", columns.get(1).get("displayName"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) captured.get("rows");
        assertEquals(1, rows.size());
        assertEquals("413000001", rows.get(0).get("mmsi"));
        assertEquals(120.1, rows.get(0).get("lng"));
    }

    @Test
    void dataset_create_unknown_column_type_falls_back_to_string() throws Exception {
        stubEnqueue();
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("a", "不认识的自定义类型", null)));
        payload.put("rows", Arrays.asList(row("a", "1")));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) capturedPayload("dataset.create").get("columns");
        // 列类型只影响样式候选，认不出就当 string，别因为一个陌生类型名把整条指令打回
        assertEquals("string", columns.get(0).get("type"));
    }

    @Test
    void dataset_create_unknown_project_is_rejected() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("a", "string", null)));
        payload.put("rows", Arrays.asList(row("a", "1")));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("项目不存在")));
    }

    @Test
    void dataset_create_requires_columns_and_rows() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> noColumns = new LinkedHashMap<>();
        noColumns.put("datasetName", "ds");
        noColumns.put("rows", Arrays.asList(row("a", "1")));
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", noColumns)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("columns")));

        Map<String, Object> noRows = new LinkedHashMap<>();
        noRows.put("datasetName", "ds");
        noRows.put("columns", Arrays.asList(column("a", "string", null)));
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", noRows)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("rows")));

        Map<String, Object> noName = new LinkedHashMap<>();
        noName.put("columns", Arrays.asList(column("a", "string", null)));
        noName.put("rows", Arrays.asList(row("a", "1")));
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", noName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("datasetName")));
    }

    @Test
    void dataset_create_duplicate_column_name_is_rejected() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("lng", "number", null), column("lng", "number", null)));
        payload.put("rows", Arrays.asList(row("lng", 1)));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isBadRequest())
                // 重名列在行对象里无法区分，只能拒
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("列名重复")));
    }

    @Test
    void dataset_create_row_key_not_in_columns_is_rejected_with_available() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("lng", "number", null), column("lat", "number", null)));
        payload.put("rows", Arrays.asList(row("lng", 120.1, "lon", 36.2)));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isBadRequest())
                // 不静默丢数据：多出来的键说明智能体理解错了列，直接拒并回可用列
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("lon")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("lng")));
    }

    @Test
    void dataset_create_nested_cell_value_is_rejected() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("geo", "geo", null)));
        payload.put("rows", Arrays.asList(row("geo", row("x", 1.0, "y", 2.0))));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("嵌套结构")));
    }

    @Test
    void dataset_create_too_many_rows_is_rejected() throws Exception {
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            rows.add(row("lng", 120.0 + i * 0.0001));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("lng", "number", null)));
        payload.put("rows", rows);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("行数过多")))
                // 超限时给出替代路径，而不是干巴巴一句「不行」
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("文件上传")));
    }

    @Test
    void dataset_create_auto_create_layer_can_be_turned_off() throws Exception {
        stubEnqueue();
        when(projectRepository.findById("p1")).thenReturn(project("p1", "演示项目"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetName", "ds");
        payload.put("columns", Arrays.asList(column("a", "string", null)));
        payload.put("rows", Arrays.asList(row("a", "1")));
        payload.put("autoCreateLayer", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("dataset.create", payload)))
                .andExpect(status().isOk());

        assertEquals(Boolean.FALSE, capturedPayload("dataset.create").get("autoCreateLayer"));
    }

    // ==================== v1.4：layer.update ====================

    @Test
    void layer_update_happy_path_keeps_layer_id_and_fields() throws Exception {
        stubEnqueue();
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("description", "测试用图层，数据来自内联示例");
        payload.put("color", "#F86624");
        payload.put("size", 8);
        payload.put("opacity", 0.6);
        payload.put("visible", false);

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capability").value("config"));

        Map<String, Object> captured = capturedPayload("layer.update");
        assertEquals("layer_1", captured.get("layerId"));
        assertEquals("测试用图层，数据来自内联示例", captured.get("description"));
        assertEquals("#F86624", captured.get("color"));
        assertEquals(8.0, captured.get("size"));
        assertEquals(0.6, captured.get("opacity"));
        assertEquals(Boolean.FALSE, captured.get("visible"));
        // 匹配到的旧名已被移除，避免与改名用的 name 混淆
        assertFalse(captured.containsKey("layerName"));
        assertFalse(captured.containsKey("name"));
    }

    @Test
    void layer_update_rename_and_clear_description() throws Exception {
        stubEnqueue();
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("name", "船舶实时（新）");
        // 备注是可选项，空串 = 清空，不算「非法输入」
        payload.put("description", "");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isOk());

        Map<String, Object> captured = capturedPayload("layer.update");
        assertEquals("船舶实时（新）", captured.get("name"));
        assertEquals("", captured.get("description"));
    }

    @Test
    void layer_update_requires_at_least_one_field() throws Exception {
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("至少要给一个")))
                // 「什么都没改」不该伪装成成功
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("visType")));
    }

    @Test
    void layer_update_unknown_layer_is_rejected() throws Exception {
        when(layerRepository.findById("layer_x")).thenReturn(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_x");
        payload.put("color", "#ffffff");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不属于该项目")));
    }

    @Test
    void layer_update_out_of_range_values_are_rejected() throws Exception {
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> opacity = new LinkedHashMap<>();
        opacity.put("layerId", "layer_1");
        opacity.put("opacity", 1.5);
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", opacity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("opacity")));

        Map<String, Object> size = new LinkedHashMap<>();
        size.put("layerId", "layer_1");
        size.put("size", -1);
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", size)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("size")));

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put("layerId", "layer_1");
        blank.put("name", "  ");
        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", blank)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("name")));
    }

    /** parser 的列名在后端就校验：语义错误 400 返回，不拖到页面执行时才失败 */
    @Test
    void layer_update_parser_field_not_in_dataset_is_rejected_with_available() throws Exception {
        Layer bound = layer("layer_1", "船舶实时", "p1");
        bound.setDatasetId("ds_1");
        when(layerRepository.findById("layer_1")).thenReturn(bound);
        when(datasetColumnRepository.findByDatasetId("ds_1")).thenReturn(Arrays.asList(
                datasetColumn("lng"), datasetColumn("lat"), datasetColumn("mmsi")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("parser", row("x", "经度", "y", "纬度"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("经度")))
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("lng")));
    }

    @Test
    void layer_update_parser_geometry_and_xy_conflict() throws Exception {
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("parser", row("geometry", "geom", "x", "lng"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("不能同时给")));
    }

    /** x/y 必须成对：只给一个通常意味着智能体把字段名写漏了 */
    @Test
    void layer_update_parser_half_pair_is_rejected() throws Exception {
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("parser", row("x", "lng"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("x,y")));
    }

    /** 数据集列查不到（如未绑数据集）→ 跳过存在性校验，别把「查不到」当成「不合法」 */
    @Test
    void layer_update_parser_skips_field_check_when_columns_unknown() throws Exception {
        stubEnqueue();
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("parser", row("x", "任意列名", "y", "另一个列名"));

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        Map<String, Object> parser = (Map<String, Object>) capturedPayload("layer.update").get("parser");
        assertEquals("任意列名", parser.get("x"));
    }

    @Test
    void layer_update_vis_type_is_passed_through() throws Exception {
        stubEnqueue();
        when(layerRepository.findById("layer_1")).thenReturn(layer("layer_1", "船舶实时", "p1"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layerId", "layer_1");
        payload.put("visType", "IconLayer");

        mockMvc.perform(post("/api/projects/p1/agent/commands")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("layer.update", payload)))
                .andExpect(status().isOk());

        // visType 是否为已注册资产只能由页面校验（后端不认识资产包）
        assertEquals("IconLayer", capturedPayload("layer.update").get("visType"));
    }

    private Map<String, Object> column(String name, String type, String displayName) {
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("name", name);
        column.put("type", type);
        if (displayName != null) {
            column.put("displayName", displayName);
        }
        return column;
    }

    private DatasetColumn datasetColumn(String name) {
        DatasetColumn column = new DatasetColumn();
        column.setColumnName(name);
        return column;
    }

    private Map<String, Object> row(Object... kv) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            row.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return row;
    }
}
