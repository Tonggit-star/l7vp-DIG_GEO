package com.antv.l7vp.model;

import lombok.Data;

@Data
public class IconItem {
    private String iconId;
    private String categoryId;
    private String libraryCode;
    private String codeName;
    private String fileName;
    private String originalName;
    private String fileType;
    private Long fileSize;
    private String url;
    private Integer sortOrder;
    private String createTime;
}
