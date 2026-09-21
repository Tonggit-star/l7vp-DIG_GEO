package com.antv.l7vp.model;

import lombok.Data;

@Data
public class DatasetRow {
    private String rowId;
    private String datasetId;
    private Integer rowIndex;
    private String rowData;
}
