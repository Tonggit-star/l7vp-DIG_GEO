package com.antv.l7vp.controller;

import com.antv.l7vp.model.DbConnection;
import com.antv.l7vp.service.DbConnectionService;
import org.springframework.beans.factory.annotation.Autowired;
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
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }

    // ==================== 数据查询（用于数据集加载/刷新） ====================

    @GetMapping("/db-connections/{id}/tables/{tableName}/data")
    public ResponseEntity<?> queryTableData(
            @PathVariable String id,
            @PathVariable String tableName) {
        try {
            DbConnection conn = dbConnService.getFullConnection(id);
            if (conn == null) return ResponseEntity.notFound().build();

            int rowCount = dbConnService.getRowCount(conn, tableName);
            DbConnectionService.TableDataResult tableData = dbConnService.queryTableWithColumns(conn, tableName);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rows", tableData.getRows());
            result.put("columns", tableData.getColumns());
            result.put("rowCount", rowCount);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return ResponseEntity.status(500).body(error);
        }
    }
}
