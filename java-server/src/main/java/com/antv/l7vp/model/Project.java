package com.antv.l7vp.model;

import lombok.Data;

import java.util.List;

@Data
public class Project {
    private String projectId;
    private String projectName;
    private String description;
    private String createTime;
    private String updateTime;
    private String thumbnail;
    private List<String> assetPackageIds;
    private String mapConfig;  // spec.map JSON (basemap, config, logoPosition, logoVisible 等)
}
