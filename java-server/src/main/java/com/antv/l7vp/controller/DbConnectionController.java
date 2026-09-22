package com.antv.l7vp.controller;

import com.antv.l7vp.model.DbConnection;
import com.antv.l7vp.service.DbConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 数据库连接配置管理 + 数据查询
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DbConnectionController {

    @Autowired
    private DbConnectionService dbConnService;

    // ==================== 连接 CRUD ====================

    @GetMapping("/db-connections")
    public List<DbConnection> listConnections() {
        return dbConnService.listConnections();
    }

    /**
     * 支持的数据源类型。类型值、默认端口、字段叫法、操作提示统一由后端给，
     * 前端只负责渲染——省得前后端各维护一份慢慢漂移。
     */
    @GetMapping("/db-connections/supported-types")
    public List<Map<String, Object>> supportedTypes() {
        return DbConnectionService.getSupportedTypes();
    }

    @PostMapping("/db-connections")
    public DbConnection createConnection(@RequestBody DbConnection conn) {
        return dbConnService.createConnection(conn);
    }

    @PutMapping("/db-connections/{id}")
    public DbConnection updateConnection(@PathVariable String id, @RequestBody DbConnection conn) {
        return dbConnService.updateConnection(id, conn);
    }

    @DeleteMapping("/db-connections/{id}")
    public ResponseEntity<Void> deleteConnection(@PathVariable String id) {
        dbConnService.deleteConnection(id);
        return ResponseEntity.ok().build();
    }

    // ==================== 连接测试 ====================

    @PostMapping("/db-connections/test")
    public ResponseEntity<?> testConnection(@RequestBody DbConnection conn) {
        try {
            dbConnService.testConnection(conn);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "连接成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("message", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== 表管理 ====================

    @GetMapping("/db-connections/{id}/tables")
    public ResponseEntity<?> listTables(@PathVariable String id) {
        try {
            DbConnection conn = dbConnService.getFullConnection(id);
            if (conn == null) return ResponseEntity.notFound().build();
            List<Map<String, String>> tables = dbConnService.listTables(conn);
            return ResponseEntity.ok(tables);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    @GetMapping("/db-connections/{id}/tables/{tableName}/preview")
    public ResponseEntity<?> previewTable(
            @PathVariable String id,
            @PathVariable String tableName,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            DbConnection conn = dbConnService.getFullConnection(id);
            if (conn == null) return ResponseEntity.notFound().build();

            // 数据量探查
            int rowCount = dbConnService.getRowCount(conn, tableName);

            DbConnectionService.TableDataResult tableData = dbConnService.previewTableWithColumns(conn, tableName, limit);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", tableData.getRows());
            result.put("columns", tableData.getColumns());
            result.put("rowCount", rowCount);
            result.put("limit", limit);
            // Redis 路径可能因上限被截断，前端据此提示用户收窄键模式
            result.put("truncated", tableData.isTruncated());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== 数据查询（用于数据集加载/刷新） ====================

    /** 单表查询的默认行数上限。防的是一张巨表把浏览器打挂（宽表问题靠选列解决，不是这个） */
    @Value("${l7vp.db-connection.max-rows:20000}")
    private int maxRows;

    /** 硬顶：请求里显式要更多也不越过它 */
    private static final int HARD_MAX_ROWS = 200000;

    /** 单次最多选多少列，防手滑把 SQL 拼爆 */
    private static final int MAX_COLUMNS = 200;

    /**
     * 解析逗号分隔的列名。空 → null（表示全列）。
     * 这里只管去空白/去重/限个数；标识符合法性交给 service 层统一校验（那里才是拼 SQL 的地方）。
     */
    private static List<String> parseColumns(String columns) {
        if (columns == null || columns.trim().isEmpty()) return null;
        List<String> list = new ArrayList<>();
        for (String part : columns.split(",")) {
            String c = part.trim();
            if (!c.isEmpty() && !list.contains(c)) list.add(c);
            if (list.size() >= MAX_COLUMNS) break;
        }
        return list.isEmpty() ? null : list;
    }

    /**
     * 查表数据（数据集运行时调用的接口）。
     *
     * @param columns 可选，逗号分隔的列名；不传 = 全列。宽表务必传——
     *                实测一个 4.5KB 的 jsonb 列能把响应从 0.9MB 顶到 23MB
     * @param limit   可选，覆盖默认行数上限
     */
    @GetMapping("/db-connections/{id}/tables/{tableName}/data")
    public ResponseEntity<?> queryTableData(
            @PathVariable String id,
            @PathVariable String tableName,
            @RequestParam(required = false) String columns,
            @RequestParam(required = false) Integer limit) {
        try {
            DbConnection conn = dbConnService.getFullConnection(id);
            if (conn == null) return ResponseEntity.notFound().build();

            List<String> selected = parseColumns(columns);
            int cap = (limit != null && limit > 0) ? Math.min(limit, HARD_MAX_ROWS) : maxRows;

            int rowCount = dbConnService.getRowCount(conn, tableName);
            DbConnectionService.TableDataResult tableData =
                dbConnService.queryTableWithColumns(conn, tableName, selected, cap);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", tableData.getRows());
            result.put("columns", tableData.getColumns());
            result.put("rowCount", rowCount);
            result.put("truncated", tableData.isTruncated());
            // 回显实际生效的选列与上限，便于排查「为什么只有这几列 / 只有这些行」
            result.put("selectedColumns", selected);
            result.put("maxRows", cap);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
