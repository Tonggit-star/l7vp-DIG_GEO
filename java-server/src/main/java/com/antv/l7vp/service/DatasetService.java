package com.antv.l7vp.service;

import com.antv.l7vp.dto.ColumnDef;
import com.antv.l7vp.dto.CreateDatasetRequest;
import com.antv.l7vp.dto.CreateDatasetResult;
import com.antv.l7vp.dto.PagedRows;
import com.antv.l7vp.model.Dataset;
import com.antv.l7vp.model.DatasetColumn;
import com.antv.l7vp.model.DatasetRow;
import com.antv.l7vp.repository.DatasetColumnRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.antv.l7vp.repository.DatasetRepository;
import com.antv.l7vp.repository.DatasetRowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DatasetService {

    @Autowired
    private DatasetRepository datasetRepository;

    @Autowired
    private DatasetColumnRepository datasetColumnRepository;

    @Autowired
    private DatasetRowRepository datasetRowRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FlinkJobService flinkJobService;

    private static final Logger log = LoggerFactory.getLogger(DatasetService.class);

    public CreateDatasetResult createDatasetWithRows(String projectId, CreateDatasetRequest request) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        log.info("[UPLOAD_DATASET] projectId={}, datasetId={}, name={}, type={}, columns={}, rows={}",
            projectId, request.getId(), request.getDatasetName(), request.getType(),
            request.getColumns() != null ? request.getColumns().size() : 0,
            request.getRows() != null ? request.getRows().size() : 0);

        // 1. 插入 DATASETS
        Dataset dataset = new Dataset();
        // 如果前端提供了 id，使用前端的 id（保持与 LIEditor 一致）
        if (request.getId() != null && !request.getId().isEmpty()) {
            dataset.setDatasetId(request.getId());
        }
        dataset.setProjectId(projectId);
        dataset.setDatasetName(request.getDatasetName());
        dataset.setType(request.getType());
        dataset.setCreateTime(now);

        // 构建 metadata
        try {
            Map<String, Object> metaMap = new java.util.LinkedHashMap<>();
            metaMap.put("columnsMeta", request.getColumns());
            metaMap.put("rowCount", request.getRows() != null ? request.getRows().size() : 0);
            dataset.setMetadata(objectMapper.writeValueAsString(metaMap));
        } catch (Exception e) {
            throw new RuntimeException("序列化dataset metadata失败", e);
        }

        datasetRepository.insert(dataset);

        // 2. 批量插入列定义
        if (request.getColumns() != null && !request.getColumns().isEmpty()) {
            List<DatasetColumn> columns = new ArrayList<>();
            for (int i = 0; i < request.getColumns().size(); i++) {
                ColumnDef colDef = request.getColumns().get(i);
                DatasetColumn column = new DatasetColumn();
                column.setDatasetId(dataset.getDatasetId());
                column.setColumnName(colDef.getName());
                column.setColumnType(colDef.getType());
                column.setColumnIndex(colDef.getIndex() != null ? colDef.getIndex() : i);
                column.setColumnComment(colDef.getComment());
                columns.add(column);
            }
            datasetColumnRepository.batchInsert(dataset.getDatasetId(), columns);
        }

        // 3. 逐行插入数据
        int rowCount = 0;
        if (request.getRows() != null && !request.getRows().isEmpty()) {
            List<DatasetRow> rowEntities = new ArrayList<>();
            for (int i = 0; i < request.getRows().size(); i++) {
                DatasetRow row = new DatasetRow();
                row.setDatasetId(dataset.getDatasetId());
                row.setRowIndex(i);
                try {
                    row.setRowData(objectMapper.writeValueAsString(request.getRows().get(i)));
                } catch (Exception e) {
                    throw new RuntimeException("序列化行数据失败", e);
                }
                rowEntities.add(row);
            }
            rowCount = request.getRows().size();
            int batchSize = Math.min(500, rowEntities.size());
            datasetRowRepository.batchInsert(dataset.getDatasetId(), rowEntities, batchSize);
            log.info("[UPLOAD_DATASET] Stored {} rows", rowCount);
        }

        CreateDatasetResult result = new CreateDatasetResult();
        result.setDatasetId(dataset.getDatasetId());
        result.setRowCount(rowCount);
        return result;
    }

    public PagedRows getRows(String datasetId, int page, int size) {
        Dataset dataset = datasetRepository.findById(datasetId);
        if (dataset == null) {
            throw new RuntimeException("数据集不存在");
        }

        Map<String, Object> rowResult = datasetRowRepository.findByDatasetId(datasetId, page, size);

        List<DatasetColumn> columns = datasetColumnRepository.findByDatasetId(datasetId);
        List<ColumnDef> columnDefs = new ArrayList<>();
        for (DatasetColumn col : columns) {
            ColumnDef def = new ColumnDef();
            def.setName(col.getColumnName());
            def.setType(col.getColumnType());
            def.setIndex(col.getColumnIndex());
            def.setComment(col.getColumnComment());
            columnDefs.add(def);
        }

        @SuppressWarnings("unchecked")
        List<DatasetRow> dbRows = (List<DatasetRow>) rowResult.get("rows");
        List<Map<String, Object>> parsedRows = new ArrayList<>();
        for (DatasetRow dr : dbRows) {
            try {
                // 每行 ROW_DATA CLOB 存一个对象
                @SuppressWarnings("unchecked")
                Map<String, Object> rowMap = objectMapper.readValue(dr.getRowData(), Map.class);
                parsedRows.add(rowMap);
            } catch (Exception e) {
                // 兼容旧格式：单行数组
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> singleRow = objectMapper.readValue(dr.getRowData(), List.class);
                    if (singleRow != null && !singleRow.isEmpty()) {
                        Map<String, Object> rowMap = new java.util.LinkedHashMap<>();
                        for (int i = 0; i < singleRow.size() && i < columnDefs.size(); i++) {
                            rowMap.put(columnDefs.get(i).getName(), singleRow.get(i));
                        }
                        parsedRows.add(rowMap);
                    }
                } catch (Exception e2) {
                    // skip
                }
            }
        }

        PagedRows result = new PagedRows();
        result.setRows(parsedRows);
        result.setTotal((Integer) rowResult.get("total"));
        result.setPage(page);
        result.setSize(size);
        result.setColumns(columnDefs);
        return result;
    }

    public void deleteDataset(String datasetId) {
        // 若是流式(kafka)数据集，先取消其 Flink 作业，避免残留作业持续回推
        flinkJobService.stopIfStreaming(datasetId);
        datasetRowRepository.deleteByDatasetId(datasetId);
        datasetColumnRepository.deleteByDatasetId(datasetId);
        datasetRepository.deleteById(datasetId);
    }

    public Dataset updateDatasetMeta(String datasetId, String name, String metadata) {
        Dataset dataset = datasetRepository.findById(datasetId);
        if (dataset == null) {
            throw new RuntimeException("数据集不存在");
        }
        if (name != null) {
            dataset.setDatasetName(name);
        }
        if (metadata != null) {
            dataset.setMetadata(metadata);
        }
        datasetRepository.update(dataset);
        return dataset;
    }

    public List<Dataset> findByProjectId(String projectId) {
        return datasetRepository.findByProjectId(projectId);
    }

    /** 流式数据推送端点校验数据集是否存在 */
    public boolean exists(String datasetId) {
        return datasetRepository.findById(datasetId) != null;
    }
}
