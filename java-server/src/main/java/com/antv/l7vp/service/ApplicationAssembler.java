package com.antv.l7vp.service;

import com.antv.l7vp.model.*;
import com.antv.l7vp.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
public class ApplicationAssembler {

    private static final Logger log = LoggerFactory.getLogger(ApplicationAssembler.class);

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private DatasetColumnRepository datasetColumnRepository;

    @Autowired
    private LayerRepository layerRepository;

    @Autowired
    private WidgetRepository widgetRepository;

    @Autowired
    private DatasetRowRepository datasetRowRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlinkJobService flinkJobService;

    /**
     * 多表 → Application JSON
     * 将分散在多表中的数据组装为前端兼容的完整 Application JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> assemble(String projectId) {
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        Map<String, Object> application = new LinkedHashMap<>();

        // === metadata ===
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("projectId", project.getProjectId());
        metadata.put("name", project.getProjectName());
        metadata.put("description", project.getDescription());
        metadata.put("createTime", project.getCreateTime());
        metadata.put("updateTime", project.getUpdateTime());
        metadata.put("thumbnail", project.getThumbnail());
        metadata.put("assetPackageIds", project.getAssetPackageIds());
        application.put("metadata", metadata);

        // === datasets ===
        List<Dataset> datasets = datasetRepository.findByProjectId(projectId);
        List<Map<String, Object>> datasetList = new ArrayList<>();
        for (Dataset ds : datasets) {
            Map<String, Object> dsJson = new LinkedHashMap<>();
            dsJson.put("id", ds.getDatasetId());
            dsJson.put("name", ds.getDatasetName());
            dsJson.put("type", ds.getType());
            dsJson.put("createTime", ds.getCreateTime());

            // 解析 metadata JSON，提取 _properties
            Map<String, Object> dsMeta = new LinkedHashMap<>();
            int rowCount = 0;
            if (ds.getMetadata() != null) {
                try {
                    dsMeta = objectMapper.readValue(ds.getMetadata(), Map.class);
                    if (dsMeta != null) {
                        if (dsMeta.containsKey("_properties")) {
                            dsJson.put("properties", dsMeta.remove("_properties"));
                        }
                        if (dsMeta.containsKey("_serviceType")) {
                            dsJson.put("serviceType", dsMeta.remove("_serviceType"));
                        }
                        if (dsMeta.containsKey("rowCount")) {
                            rowCount = ((Number) dsMeta.get("rowCount")).intValue();
                        }
                    }
                } catch (Exception e) {
                    // ignore parse errors
                }
            }
            dsJson.put("metadata", dsMeta);

            // 列定义
            List<DatasetColumn> columns = datasetColumnRepository.findByDatasetId(ds.getDatasetId());
            List<Map<String, Object>> columnsList = new ArrayList<>();
            for (DatasetColumn col : columns) {
                Map<String, Object> colJson = new LinkedHashMap<>();
                colJson.put("name", col.getColumnName());
                colJson.put("type", col.getColumnType());
                colJson.put("index", col.getColumnIndex());
                if (col.getColumnComment() != null && !col.getColumnComment().isEmpty()) {
                    colJson.put("displayName", col.getColumnComment());
                }
                columnsList.add(colJson);
            }
            dsJson.put("columns", columnsList);

            // 筛选器配置（从 DATASETS.FILTER CLOB 恢复）
            if (ds.getFilter() != null && !ds.getFilter().isEmpty()) {
                try {
                    Map<String, Object> filterObj = objectMapper.readValue(ds.getFilter(), Map.class);
                    if (filterObj != null) {
                        dsJson.put("filter", filterObj);
                    }
                } catch (Exception e) {
                    // ignore filter parse errors
                }
            }

            // 数据行懒加载标记 / 流式数据集标记
            // 流式数据集（metadata.stream=true）：data 留空，前端通过 WebSocket 实时注入，
            // 不走分页懒加载（_lazy），也不入 DATASET_ROWS。
            boolean isStream = Boolean.TRUE.equals(dsMeta.get("stream"));
            if ("local".equals(ds.getType())) {
                dsJson.put("data", new ArrayList<>());
                if (isStream) {
                    dsJson.put("_stream", true);
                } else {
                    dsJson.put("_lazy", true);
                    dsJson.put("_rowCount", rowCount);
                }
            }

            datasetList.add(dsJson);
        }
        application.put("datasets", datasetList);

        // === spec ===
        Map<String, Object> spec = new LinkedHashMap<>();

        // spec.map (basemap config) — 从存储恢复
        Map<String, Object> mapConfig = new LinkedHashMap<>();
        String storedMapConfig = project.getMapConfig();
        if (storedMapConfig != null && !storedMapConfig.isEmpty()) {
            try {
                mapConfig = objectMapper.readValue(storedMapConfig, Map.class);
            } catch (Exception e) {
                mapConfig.put("type", "Map");
            }
        } else {
            mapConfig.put("type", "Map");
        }
        spec.put("map", mapConfig);

        // spec.layers
        List<Layer> layers = layerRepository.findByProjectId(projectId);
        List<Map<String, Object>> layerList = new ArrayList<>();
        for (Layer layer : layers) {
            Map<String, Object> layerJson = new LinkedHashMap<>();
            layerJson.put("id", layer.getLayerId());
            layerJson.put("name", layer.getLayerName());
            layerJson.put("type", layer.getType());
            layerJson.put("dataset", layer.getDatasetId());
            layerJson.put("order", layer.getLayerOrder());
            // vis_config 作为 JSON 对象解析
            if (layer.getVisConfig() != null) {
                try {
                    Map<String, Object> visConfig = objectMapper.readValue(layer.getVisConfig(), Map.class);
                    if (visConfig != null) {
                        visConfig.forEach((k, v) -> layerJson.put(k, v));
                    }
                } catch (Exception e) {
                    layerJson.put("visConfig", layer.getVisConfig());
                }
            }
            layerList.add(layerJson);
        }
        spec.put("layers", layerList);

        // spec.widgets
        List<Widget> widgets = widgetRepository.findByProjectId(projectId);
        List<Map<String, Object>> widgetList = new ArrayList<>();
        for (Widget widget : widgets) {
            Map<String, Object> widgetJson = new LinkedHashMap<>();
            widgetJson.put("id", widget.getWidgetId());
            widgetJson.put("name", widget.getWidgetName());
            widgetJson.put("type", widget.getType());
            widgetJson.put("containerId", widget.getContainerId());
            widgetJson.put("slot", widget.getSlot());
            widgetJson.put("order", widget.getWidgetOrder());

            // 先解析 properties，用于兼容旧数据（container 引用可能存储在 properties 中）
            Map<String, Object> props = null;
            if (widget.getProperties() != null) {
                try {
                    props = objectMapper.readValue(widget.getProperties(), Map.class);
                } catch (Exception e) {
                    widgetJson.put("properties", widget.getProperties());
                }
            }

            // 重建 container 对象：优先用 DB 列的 containerId，降级到 properties 中的 container
            @SuppressWarnings("unchecked")
            Map<String, Object> propsContainer = (props != null && props.containsKey("container"))
                ? (Map<String, Object>) props.get("container") : null;
            String effectiveContainerId = (widget.getContainerId() != null && !widget.getContainerId().isEmpty())
                ? widget.getContainerId()
                : (propsContainer != null && propsContainer.get("id") != null
                    ? propsContainer.get("id").toString() : null);
            String effectiveSlot = (widget.getSlot() != null && !widget.getSlot().isEmpty())
                ? widget.getSlot()
                : (propsContainer != null && propsContainer.get("slot") != null
                    ? propsContainer.get("slot").toString() : null);

            if (effectiveContainerId != null && !effectiveContainerId.isEmpty()) {
                Map<String, Object> container = new LinkedHashMap<>();
                container.put("id", effectiveContainerId);
                container.put("slot", effectiveSlot != null ? effectiveSlot : "controls");
                widgetJson.put("container", container);
            }

            if (props != null) {
                // 移除已提取到顶层/container 的字段，避免前端拿到过期引用
                props.remove("container");
                props.remove("containerId");
                props.remove("slot");
                props.remove("order");
                props.forEach((k, v) -> widgetJson.put(k, v));
            }
            widgetList.add(widgetJson);
        }
        spec.put("widgets", widgetList);

        application.put("spec", spec);

        return application;
    }

    /**
     * Application JSON → 多表
     * 将前端传来的 Application JSON 拆解并分别写入各表（含 DATASET_ROWS）
     */
    @SuppressWarnings("unchecked")
    public void disassemble(String projectId, Map<String, Object> application) {
        log.info("[DISASSEMBLE] projectId={}, topKeys={}", projectId, application.keySet());
        Project project = projectRepository.findById(projectId);
        if (project == null) {
            throw new RuntimeException("项目不存在");
        }

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 若请求体包装了 applicationConfig，则展开使用其内容
        Map<String, Object> appData = application;
        if (application.containsKey("applicationConfig")) {
            appData = (Map<String, Object>) application.get("applicationConfig");
        }

        // === 更新 PROJECTS ===
        Map<String, Object> metadata = (Map<String, Object>) appData.get("metadata");
        if (metadata != null) {
            if (metadata.containsKey("name")) {
                Object nameObj = metadata.get("name");
                project.setProjectName(nameObj != null ? nameObj.toString() : null);
            }
            if (metadata.containsKey("description")) {
                Object descObj = metadata.get("description");
                project.setDescription(descObj != null ? descObj.toString() : null);
            }
            if (metadata.containsKey("thumbnail")) {
                Object thumbObj = metadata.get("thumbnail");
                project.setThumbnail(thumbObj != null ? thumbObj.toString() : null);
            }
            if (metadata.containsKey("assetPackageIds")) {
                Object apids = metadata.get("assetPackageIds");
                if (apids instanceof List) {
                    project.setAssetPackageIds((List<String>) apids);
                }
            }
        }
        project.setUpdateTime(now);
        projectRepository.update(project);

        // 收集「不在新配置中」的历史数据集 ID。真正删除推迟到 spec(layers/widgets) 归并对齐之后执行，
        // 使被本保存同步删除的图层引用先消失，孤儿数据集才可被清理（避免只删图层、残留数据集的脏状态）。
        List<String> orphanDatasetIds = new ArrayList<>();

        // === 对比处理 DATASETS (不操作 ROWS) ===
        // 记录 datasetId 重映射（硬编码 ID 冲突时 → UUID），后续用于修正 layer 引用
        Map<String, String> datasetIdRemap = new LinkedHashMap<>();
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) appData.get("datasets");
        log.info("[DISASSEMBLE] datasets count={}", datasets != null ? datasets.size() : 0);
        if (datasets != null) {
            List<Dataset> existingDatasets = datasetRepository.findByProjectId(projectId);
            Map<String, Dataset> existingMap = new LinkedHashMap<>();
            for (Dataset ds : existingDatasets) {
                existingMap.put(ds.getDatasetId(), ds);
            }

            Set<String> newIds = new HashSet<>();

            for (Map<String, Object> dsJson : datasets) {
                String dsId = dsJson.get("id") != null ? dsJson.get("id").toString() : null;
                if (dsId == null || dsId.isEmpty()) continue;

                Dataset dataset = existingMap.get(dsId);
                if (dataset == null) {
                    // 新数据集 — 检查全局唯一性（不同项目不可共用相同 dataset ID）
                    Dataset globalExisting = datasetRepository.findById(dsId);
                    if (globalExisting != null) {
                        String newDsId = java.util.UUID.randomUUID().toString();
                        datasetIdRemap.put(dsId, newDsId);
                        dsId = newDsId;
                        dsJson.put("id", dsId);
                    }
                    dataset = new Dataset();
                    dataset.setDatasetId(dsId);
                    dataset.setProjectId(projectId);
                    dataset.setCreateTime(now);
                    fillDatasetFields(dataset, dsJson);
                    datasetRepository.insert(dataset);

                    // 插入列定义
                    List<Map<String, Object>> columns = (List<Map<String, Object>>) dsJson.get("columns");
                    if (columns != null) {
                        List<DatasetColumn> columnEntities = new ArrayList<>();
                        for (int i = 0; i < columns.size(); i++) {
                            Map<String, Object> colJson = columns.get(i);
                            DatasetColumn col = new DatasetColumn();
                            col.setDatasetId(dsId);
                            col.setColumnName(colJson.get("name") != null ? colJson.get("name").toString() : null);
                            col.setColumnType(colJson.get("type") != null ? colJson.get("type").toString() : null);
                            Object idx = colJson.get("index");
                            col.setColumnIndex(idx != null ? ((Number) idx).intValue() : i);
                            // 保存字段注释（displayName）
                            Object comment = colJson.get("displayName");
                            if (comment != null && !comment.toString().isEmpty()) {
                                col.setColumnComment(comment.toString());
                            }
                            columnEntities.add(col);
                        }
                        datasetColumnRepository.batchInsert(dsId, columnEntities);
                    }

                    // 插入数据行（逐行存储）
                    Object dataObj = dsJson.get("data");
                    log.info("[DISASSEMBLE] dataset={}, hasData={}, dataSize={}",
                        dsId, dataObj != null, dataObj instanceof List ? ((List<?>) dataObj).size() : 0);
                    if (dataObj instanceof List && !((List<?>) dataObj).isEmpty()) {
                        List<?> dataList = (List<?>) dataObj;
                        List<DatasetRow> rowEntities = new ArrayList<>();
                        for (int ri = 0; ri < dataList.size(); ri++) {
                            DatasetRow row = new DatasetRow();
                            row.setDatasetId(dsId);
                            row.setRowIndex(ri);
                            try {
                                row.setRowData(objectMapper.writeValueAsString(dataList.get(ri)));
                            } catch (Exception e) {
                                log.error("[DISASSEMBLE] Serialize error at {}", ri, e);
                                throw new RuntimeException("序列化行数据失败", e);
                            }
                            rowEntities.add(row);
                        }
                        datasetRowRepository.batchInsert(dsId, rowEntities, Math.min(500, rowEntities.size()));
                        log.info("[DISASSEMBLE] Stored {} rows", dataList.size());
                    }
                } else {
                    // 已存在 — 更新元数据
                    fillDatasetFields(dataset, dsJson);
                    datasetRepository.update(dataset);

                    // 更新列定义: 先删后插
                    datasetColumnRepository.deleteByDatasetId(dsId);
                    List<Map<String, Object>> columns = (List<Map<String, Object>>) dsJson.get("columns");
                    if (columns != null) {
                        List<DatasetColumn> columnEntities = new ArrayList<>();
                        for (int i = 0; i < columns.size(); i++) {
                            Map<String, Object> colJson = columns.get(i);
                            DatasetColumn col = new DatasetColumn();
                            col.setDatasetId(dsId);
                            col.setColumnName(colJson.get("name") != null ? colJson.get("name").toString() : null);
                            col.setColumnType(colJson.get("type") != null ? colJson.get("type").toString() : null);
                            Object idx = colJson.get("index");
                            col.setColumnIndex(idx != null ? ((Number) idx).intValue() : i);
                            // 保存字段注释（displayName）
                            Object comment = colJson.get("displayName");
                            if (comment != null && !comment.toString().isEmpty()) {
                                col.setColumnComment(comment.toString());
                            }
                            columnEntities.add(col);
                        }
                        datasetColumnRepository.batchInsert(dsId, columnEntities);
                    }

                    // 更新数据行: 先删后插（逐行存储）
                    Object dataObj = dsJson.get("data");
                    log.info("[DISASSEMBLE-UPDATE] dataset={}, hasData={}, dataSize={}",
                        dsId, dataObj != null, dataObj instanceof List ? ((List<?>) dataObj).size() : 0);
                    if (dataObj instanceof List && !((List<?>) dataObj).isEmpty()) {
                        datasetRowRepository.deleteByDatasetId(dsId);
                        List<?> dataList = (List<?>) dataObj;
                        List<DatasetRow> rowEntities = new ArrayList<>();
                        for (int ri = 0; ri < dataList.size(); ri++) {
                            DatasetRow row = new DatasetRow();
                            row.setDatasetId(dsId);
                            row.setRowIndex(ri);
                            try {
                                row.setRowData(objectMapper.writeValueAsString(dataList.get(ri)));
                            } catch (Exception e) {
                                throw new RuntimeException("序列化行数据失败", e);
                            }
                            rowEntities.add(row);
                        }
                        datasetRowRepository.batchInsert(dsId, rowEntities, Math.min(500, rowEntities.size()));
                    }
                }

                // 记录本次配置最终保留下来的数据集 ID（dsId 可能因全局唯一性冲突被重映射成新 UUID）。
                // 判定「不在新配置中的历史数据集」依赖这个集合——此前它从未被填充，导致每个历史数据集都
                // 被当作孤儿处理：无数据行、且图层尚未写入 DB 的流式数据集会被当场删除，随后插入引用它的
                // 图层即违反 FK_LAYERS_DATASET，拖垮整张项目保存（前端看到图层，DB 却没有任何落库）。
                newIds.add(dsId);
            }

            // 收集孤儿数据集候选（不在新配置中的历史数据集），真正的删除放到 spec 归并之后。
            for (String existingId : existingMap.keySet()) {
                if (!newIds.contains(existingId)) {
                    orphanDatasetIds.add(existingId);
                }
            }
        }

        // === 对比处理 LAYERS ===
        Map<String, Object> spec = (Map<String, Object>) appData.get("spec");
        log.info("[DISASSEMBLE] spec present={}, specKeys={}", spec != null, spec != null ? spec.keySet() : "N/A");
        if (spec != null) {
            // 持久化 spec.map（地图底图配置）
            Map<String, Object> specMap = (Map<String, Object>) spec.get("map");
            if (specMap != null) {
                try {
                    project.setMapConfig(objectMapper.writeValueAsString(specMap));
                    projectRepository.update(project);
                } catch (Exception e) {
                    // ignore map config serialization errors
                }
            }

            List<Map<String, Object>> layers = (List<Map<String, Object>>) spec.get("layers");
            if (layers != null) {
                List<Layer> existingLayers = layerRepository.findByProjectId(projectId);
                Map<String, Layer> existingLayerMap = new LinkedHashMap<>();
                for (Layer layer : existingLayers) {
                    existingLayerMap.put(layer.getLayerId(), layer);
                }

                Set<String> newLayerIds = new HashSet<>();

                for (int i = 0; i < layers.size(); i++) {
                    Map<String, Object> layerJson = layers.get(i);
                    String layerId = layerJson.get("id") != null ? layerJson.get("id").toString() : null;
                    if (layerId == null || layerId.isEmpty()) continue;

                    // 重映射 datasetId 引用（数据集 ID 可能因冲突被重新生成）
                    if (layerJson.containsKey("sourceConfig")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> srcCfg = (Map<String, Object>) layerJson.get("sourceConfig");
                        if (srcCfg != null && srcCfg.containsKey("datasetId")) {
                            String refDsId = srcCfg.get("datasetId") != null ? srcCfg.get("datasetId").toString() : null;
                            if (refDsId != null && datasetIdRemap.containsKey(refDsId)) {
                                srcCfg.put("datasetId", datasetIdRemap.get(refDsId));
                            }
                        }
                    }
                    // 顶层 "dataset" 字段同样需要重映射（此前仅修正了 sourceConfig.datasetId，两者可能并存）
                    if (layerJson.containsKey("dataset")) {
                        Object topDs = layerJson.get("dataset");
                        String topDsId = topDs != null ? topDs.toString() : null;
                        if (topDsId != null && datasetIdRemap.containsKey(topDsId)) {
                            layerJson.put("dataset", datasetIdRemap.get(topDsId));
                        }
                    }

                    // 悬空图层保护：解析图层最终绑定的数据集 id（顶层 "dataset"，否则 sourceConfig.datasetId，
                    // 与 fillLayerFields 的解析顺序保持一致）。若该数据集既非本次新建、也不存在于库中，
                    // 插入本图层将违反 FK_LAYERS_DATASET 并回滚整张项目保存 → 直接告警跳过，避免整单失败。
                    String boundDsId = layerJson.get("dataset") != null ? layerJson.get("dataset").toString() : null;
                    if (boundDsId == null && layerJson.containsKey("sourceConfig")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> srcCfg = (Map<String, Object>) layerJson.get("sourceConfig");
                        if (srcCfg != null && srcCfg.get("datasetId") != null) {
                            boundDsId = srcCfg.get("datasetId").toString();
                        }
                    }
                    if (boundDsId != null && datasetRepository.findById(boundDsId) == null) {
                        log.warn("[DISASSEMBLE] Skip layer {} (name={}): referenced dataset {} does not exist, skip to avoid FK_LAYERS_DATASET violation",
                            layerId, layerJson.get("name"), boundDsId);
                        continue;
                    }

                    boolean isNew = false;
                    Layer layer = existingLayerMap.get(layerId);
                    if (layer == null) {
                        // 检查全局唯一性
                        Layer globalExisting = layerRepository.findById(layerId);
                        if (globalExisting != null) {
                            layerId = java.util.UUID.randomUUID().toString();
                            layerJson.put("id", layerId);
                        }
                        layer = new Layer();
                        layer.setLayerId(layerId);
                        layer.setProjectId(projectId);
                        layer.setCreateTime(now);
                        isNew = true;
                    }
                    newLayerIds.add(layerId);
                    fillLayerFields(layer, layerJson, i);
                    if (isNew) {
                        layerRepository.insert(layer);
                    } else {
                        layerRepository.update(layer);
                    }
                }

                for (String id : existingLayerMap.keySet()) {
                    if (!newLayerIds.contains(id)) {
                        layerRepository.deleteById(id);
                    }
                }
            }

            // === 对比处理 WIDGETS ===
            List<Map<String, Object>> widgets = (List<Map<String, Object>>) spec.get("widgets");
            log.info("[DISASSEMBLE] widgets count in spec={}", widgets != null ? widgets.size() : "NULL");
            if (widgets != null) {
                log.info("[DISASSEMBLE] processing {} widgets", widgets.size());
                List<Widget> existingWidgets = widgetRepository.findByProjectId(projectId);
                Map<String, Widget> existingWidgetMap = new LinkedHashMap<>();
                for (Widget widget : existingWidgets) {
                    existingWidgetMap.put(widget.getWidgetId(), widget);
                }

                Set<String> newWidgetIds = new HashSet<>();
                // 记录 widgetId 重映射（全局唯一性冲突时 → UUID），用于修正子 widget 的 container 引用
                Map<String, String> widgetIdRemap = new LinkedHashMap<>();

                for (int i = 0; i < widgets.size(); i++) {
                    Map<String, Object> widgetJson = widgets.get(i);
                    String widgetId = widgetJson.get("id") != null ? widgetJson.get("id").toString() : null;
                    log.info("[DISASSEMBLE] widget {} id={}, name={}, type={}, keys={}",
                        i, widgetId, widgetJson.get("name"), widgetJson.get("type"), widgetJson.keySet());
                    if (widgetId == null || widgetId.isEmpty()) {
                        log.warn("[DISASSEMBLE] widget {} skipped (no id)", i);
                        continue;
                    }

                    // 将 container: { id, slot } 转换为 containerId / slot，以匹配 DB 模型
                    if (widgetJson.containsKey("container")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> container = (Map<String, Object>) widgetJson.get("container");
                        if (container != null) {
                            // containerId 不存在或为 null/空时，从 container.id 提取
                            Object existingCid = widgetJson.get("containerId");
                            if (container.containsKey("id")
                                && (existingCid == null || "".equals(existingCid.toString()))) {
                                widgetJson.put("containerId", container.get("id"));
                            }
                            // slot 不存在或为 null/空时，从 container.slot 提取
                            Object existingSlot = widgetJson.get("slot");
                            if (container.containsKey("slot")
                                && (existingSlot == null || "".equals(existingSlot.toString()))) {
                                widgetJson.put("slot", container.get("slot"));
                            }
                        }
                    }

                    boolean isNew = false;
                    Widget widget = existingWidgetMap.get(widgetId);
                    if (widget == null) {
                        // 检查全局唯一性
                        Widget globalExisting = widgetRepository.findById(widgetId);
                        if (globalExisting != null) {
                            String newWidgetId = java.util.UUID.randomUUID().toString();
                            widgetIdRemap.put(widgetId, newWidgetId);
                            widgetId = newWidgetId;
                            widgetJson.put("id", widgetId);
                        }
                        widget = new Widget();
                        widget.setWidgetId(widgetId);
                        widget.setProjectId(projectId);
                        isNew = true;
                    }
                    newWidgetIds.add(widgetId);
                    fillWidgetFields(widget, widgetJson, i);

                    if (isNew) {
                        widgetRepository.insert(widget);
                        log.info("[DISASSEMBLE] widget {} INSERTED id={}, projectId={}", i, widgetId, projectId);
                    } else {
                        widgetRepository.update(widget);
                        log.info("[DISASSEMBLE] widget {} UPDATED id={}", i, widgetId);
                    }
                }

                // 若容器 widget 的 ID 被重映射，修正子 widget 的 containerId 引用
                if (!widgetIdRemap.isEmpty()) {
                    log.info("[DISASSEMBLE] widgetIdRemap: {}", widgetIdRemap);
                    for (Map<String, Object> wj : widgets) {
                        String wid = wj.get("id") != null ? wj.get("id").toString() : null;
                        if (wid == null) continue;

                        boolean needsUpdate = false;

                        // 修正 containerId
                        if (wj.containsKey("containerId")) {
                            String refId = wj.get("containerId") != null
                                ? wj.get("containerId").toString() : null;
                            if (refId != null && widgetIdRemap.containsKey(refId)) {
                                String newRefId = widgetIdRemap.get(refId);
                                wj.put("containerId", newRefId);
                                needsUpdate = true;
                                log.info("[DISASSEMBLE] widget {} containerId remapped: {} -> {}", wid, refId, newRefId);
                            }
                        }

                        // 修正 container.id
                        @SuppressWarnings("unchecked")
                        Map<String, Object> container = (Map<String, Object>) wj.get("container");
                        if (container != null && container.containsKey("id")) {
                            String refId = container.get("id") != null
                                ? container.get("id").toString() : null;
                            if (refId != null && widgetIdRemap.containsKey(refId)) {
                                String newRefId = widgetIdRemap.get(refId);
                                container.put("id", newRefId);
                                needsUpdate = true;
                            }
                        }

                        // 同步更新数据库记录
                        if (needsUpdate) {
                            Widget existingW = widgetRepository.findById(wid);
                            if (existingW != null) {
                                fillWidgetFields(existingW, wj, existingW.getWidgetOrder());
                                widgetRepository.update(existingW);
                                log.info("[DISASSEMBLE] widget {} DB updated with remapped container", wid);
                            }
                        }
                    }
                }

                for (String id : existingWidgetMap.keySet()) {
                    if (!newWidgetIds.contains(id)) {
                        widgetRepository.deleteById(id);
                    }
                }
            }
        }

        // === 清理不在新配置中的孤儿数据集 ===
        // 已推迟到 spec(layers/widgets) 归并对齐之后执行：本保存中同步删除的图层此刻已从 DB 移除，
        // 不再因「仍被引用」被误跳过 → 孤儿数据集可被真正清理，避免只删图层、残留数据集。
        for (String existingId : orphanDatasetIds) {
            // 跳过有数据行的 dataset（可能由 upload 端点单独创建，尚未同步到 app config）
            Map<String, Object> rowCheck = datasetRowRepository.findByDatasetId(existingId, 0, 1);
            int existingRowCount = rowCheck.get("total") != null ? ((Number) rowCheck.get("total")).intValue() : 0;
            if (existingRowCount > 0) {
                log.info("[DISASSEMBLE] Skip delete orphan dataset {} (has {} rows)", existingId, existingRowCount);
                continue;
            }
            // 跳过仍被图层引用的 dataset（如 tile 底图数据集）
            List<Layer> refLayers = layerRepository.findByDatasetId(existingId);
            if (refLayers != null && !refLayers.isEmpty()) {
                log.info("[DISASSEMBLE] Skip delete orphan dataset {} (referenced by {} layers)", existingId, refLayers.size());
                continue;
            }
            // 流式数据集先从项目配置移除时，取消其 Flink 作业
            flinkJobService.stopIfStreaming(existingId);
            datasetRowRepository.deleteByDatasetId(existingId);
            datasetColumnRepository.deleteByDatasetId(existingId);
            datasetRepository.deleteById(existingId);
            log.info("[DISASSEMBLE] Deleted orphan dataset {}", existingId);
        }
    }

    private void fillDatasetFields(Dataset dataset, Map<String, Object> json) {
        if (json.containsKey("name")) {
            dataset.setDatasetName(json.get("name") != null ? json.get("name").toString() : null);
        }
        if (json.containsKey("type")) {
            dataset.setType(json.get("type") != null ? json.get("type").toString() : null);
        }
        // 将 metadata + properties 合并存入 METADATA CLOB
        // 先保留 DB 中已有的 metadata（如 rowCount、columnsMeta），再覆盖传入的新字段
        Map<String, Object> metaToStore = new LinkedHashMap<>();
        if (dataset.getMetadata() != null) {
            try {
                Map<String, Object> existingMeta = objectMapper.readValue(dataset.getMetadata(), Map.class);
                if (existingMeta != null) {
                    metaToStore.putAll(existingMeta);
                }
            } catch (Exception ignored) {}
        }
        if (json.containsKey("metadata")) {
            try {
                Object metaObj = json.get("metadata");
                if (metaObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) metaObj;
                    metaToStore.putAll(m);  // 覆盖已有字段
                }
            } catch (Exception ignored) {}
        }
        // 保存 serviceType（远程/动态数据集需要）
        if (json.containsKey("serviceType")) {
            metaToStore.put("_serviceType", json.get("serviceType"));
        }
        if (json.containsKey("properties")) {
            metaToStore.put("_properties", json.get("properties"));
        }
        try {
            dataset.setMetadata(metaToStore.isEmpty() ? null : objectMapper.writeValueAsString(metaToStore));
        } catch (Exception e) {
            dataset.setMetadata(null);
        }
        // 存储筛选器配置到 FILTER CLOB 字段
        if (json.containsKey("filter")) {
            try {
                dataset.setFilter(objectMapper.writeValueAsString(json.get("filter")));
            } catch (Exception e) {
                dataset.setFilter(null);
            }
        }
    }

    private void fillLayerFields(Layer layer, Map<String, Object> json, int order) {
        if (json.containsKey("name")) {
            layer.setLayerName(json.get("name") != null ? json.get("name").toString() : null);
        }
        if (json.containsKey("type")) {
            layer.setType(json.get("type") != null ? json.get("type").toString() : null);
        }
        // dataset 引用可能在顶层 "dataset" 或嵌套在 "sourceConfig.datasetId"
        String dsId = null;
        if (json.containsKey("dataset")) {
            Object dsObj = json.get("dataset");
            dsId = dsObj != null ? dsObj.toString() : null;
        }
        if (dsId == null && json.containsKey("sourceConfig")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> srcCfg = (Map<String, Object>) json.get("sourceConfig");
            if (srcCfg != null && srcCfg.containsKey("datasetId")) {
                Object srcDsId = srcCfg.get("datasetId");
                dsId = srcDsId != null ? srcDsId.toString() : null;
            }
        }
        layer.setDatasetId(dsId);
        layer.setLayerOrder(order);

        // 构建 vis_config: 将 json 中不在标准字段中的值合并为 vis_config
        Set<String> standardFields = new HashSet<>(Arrays.asList("id", "name", "type", "dataset", "order", "createTime"));
        Map<String, Object> visConfig = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            if (!standardFields.contains(entry.getKey())) {
                visConfig.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            layer.setVisConfig(visConfig.isEmpty() ? null : objectMapper.writeValueAsString(visConfig));
        } catch (Exception e) {
            layer.setVisConfig(null);
        }
    }

    private void fillWidgetFields(Widget widget, Map<String, Object> json, int order) {
        if (json.containsKey("name")) {
            widget.setWidgetName(json.get("name") != null ? json.get("name").toString() : null);
        }
        if (json.containsKey("type")) {
            widget.setType(json.get("type") != null ? json.get("type").toString() : null);
        }
        if (json.containsKey("containerId")) {
            Object cid = json.get("containerId");
            widget.setContainerId(cid != null ? cid.toString() : null);
        }
        if (json.containsKey("slot")) {
            Object slotObj = json.get("slot");
            widget.setSlot(slotObj != null ? slotObj.toString() : null);
        }
        widget.setWidgetOrder(order);

        // 构建 properties: 将 json 中不在标准字段中的值合并为 properties
        // container/containerId/slot 都是 DB 列，不应存入 properties（避免前后端 container 引用不一致）
        Set<String> standardFields = new HashSet<>(Arrays.asList(
            "id", "name", "type", "containerId", "slot", "order", "container"
        ));
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : json.entrySet()) {
            if (!standardFields.contains(entry.getKey())) {
                props.put(entry.getKey(), entry.getValue());
            }
        }
        try {
            widget.setProperties(props.isEmpty() ? null : objectMapper.writeValueAsString(props));
        } catch (Exception e) {
            widget.setProperties(null);
        }
    }
}
