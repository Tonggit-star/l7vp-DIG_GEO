package com.antv.l7vp.model;

import lombok.Data;

@Data
public class DatasetColumn {
    private String columnId;
    private String datasetId;
    private String columnName;
    private String columnType;
    private Integer columnIndex;
    private String columnComment;
}
