package com.antv.l7vp.model;

import lombok.Data;

@Data
public class Widget {
    private String widgetId;
    private String projectId;
    private String containerId;
    private String widgetName;
    private String type;
    private String properties;
    private String slot;
    private Integer widgetOrder;
}
