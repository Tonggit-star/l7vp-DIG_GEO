package com.antv.l7vp.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class CreateDatasetRequest {
    private String id;  // 可选，前端生成的 dataset ID
    private String datasetName;
    private String type;
    private List<ColumnDef> columns;
    private List<Map<String, Object>> rows;
}
