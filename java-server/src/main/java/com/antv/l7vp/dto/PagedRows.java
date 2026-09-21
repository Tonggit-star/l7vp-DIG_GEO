package com.antv.l7vp.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PagedRows {
    private List<Map<String, Object>> rows;
    private int total;
    private int page;
    private int size;
    private List<ColumnDef> columns;
}
