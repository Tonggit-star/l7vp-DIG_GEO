package com.antv.l7vp.model;

import lombok.Data;

@Data
public class Layer {
    private String layerId;
    private String projectId;
    private String datasetId;
    private String layerName;
    private String type;
    private String visConfig;
    private Integer layerOrder;
    private String createTime;
}
