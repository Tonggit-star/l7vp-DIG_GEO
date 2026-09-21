package com.antv.l7vp.model;

import lombok.Data;

@Data
public class Dataset {
    private String datasetId;
    private String projectId;
    private String datasetName;
    private String type;
    private String metadata;
    private String filter;
    private String createTime;
}
