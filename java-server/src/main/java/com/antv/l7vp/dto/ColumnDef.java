package com.antv.l7vp.dto;

import lombok.Data;

@Data
public class ColumnDef {
    private String name;
    private String type;
    private Integer index;
    private String comment;
}
