package com.antv.l7vp.service;

import com.antv.l7vp.model.DbConnection;
import com.antv.l7vp.repository.DbConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 数据库连接管理服务：CRUD + 测试连接 + 表列表 + 数据预览
 */
@Service
public class DbConnectionService {

    private static final Logger log = LoggerFactory.getLogger(DbConnectionService.class);

    @Autowired
    private DbConnectionRepository repository;

    // ==================== CRUD ====================

    public List<DbConnection> listConnections() {
        List<DbConnection> conns = repository.findAll();
        // 密码脱敏
        for (DbConnection c : conns) {
            if (c.getPassword() != null && c.getPassword().length() > 2) {
                c.setPassword("***");
            }
        }
        return conns;
    }

    public DbConnection createConnection(DbConnection conn) {
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        conn.setCreateTime(now);
        conn.setUpdateTime(now);
        return repository.insert(conn);
    }

    public DbConnection updateConnection(String id, DbConnection conn) {
        DbConnection existing = repository.findById(id);
        if (existing == null) throw new RuntimeException("连接配置不存在");
        conn.setConnId(id);
        conn.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return repository.update(conn);
    }

    public void deleteConnection(String id) {
        repository.deleteById(id);
    }

    // ==================== 连接管理 ====================

    private String buildJdbcUrl(DbConnection conn) {
        if ("MySQL".equalsIgnoreCase(conn.getDbType())) {
            // MySQL / Doris 使用同一驱动
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                conn.getHost(), conn.getPort(), conn.getSchemaName() != null ? conn.getSchemaName() : "");
        } else if ("Dameng".equalsIgnoreCase(conn.getDbType())) {
            return String.format("jdbc:dm://%s:%d/%s",
                conn.getHost(), conn.getPort(), conn.getSchemaName() != null ? conn.getSchemaName() : "");
        }
        throw new RuntimeException("不支持的数据库类型: " + conn.getDbType());
    }

    private String getDriverClass(String dbType) {
        if ("MySQL".equalsIgnoreCase(dbType)) {
            return "com.mysql.cj.jdbc.Driver";
        } else if ("Dameng".equalsIgnoreCase(dbType)) {
            return "dm.jdbc.driver.DmDriver";
        }
        throw new RuntimeException("不支持的数据库类型: " + dbType);
    }

    /**
     * 测试数据库连接
     */
    public boolean testConnection(DbConnection conn) {
        String url = buildJdbcUrl(conn);
        String driver = getDriverClass(conn.getDbType());
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + driver, e);
        }
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword())) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute("SELECT 1");
            }
            return true;
        } catch (SQLException e) {
            log.error("数据库连接测试失败: {}", e.getMessage());
            throw new RuntimeException("连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 schema 下的表列表（含注释）
     * 返回 [{"name": "table_name", "comment": "中文注释"}, ...]
     */
    public List<Map<String, String>> listTables(DbConnection conn) {
        String url = buildJdbcUrl(conn);
        String driver = getDriverClass(conn.getDbType());
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + driver, e);
        }
        List<Map<String, String>> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword())) {
            DatabaseMetaData meta = c.getMetaData();
            String schemaPattern = conn.getSchemaName();
            if (schemaPattern == null || schemaPattern.isEmpty()) {
                schemaPattern = conn.getUsername();
            }
            // 对于 MySQL/Doris, schema = database name; 对于 Dameng, schema = schema name
            if ("Dameng".equalsIgnoreCase(conn.getDbType())) {
                schemaPattern = conn.getSchemaName();
            }
            try (ResultSet rs = meta.getTables(null, schemaPattern, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    Map<String, String> table = new LinkedHashMap<>();
                    table.put("name", rs.getString("TABLE_NAME"));
                    String remarks = rs.getString("REMARKS");
                    table.put("comment", remarks != null ? remarks : "");
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            log.error("获取表列表失败: {}", e.getMessage());
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }
        tables.sort((a, b) -> a.get("name").compareToIgnoreCase(b.get("name")));
        return tables;
    }

    /**
     * 获取指定表的数据行数
     */
    public int getRowCount(DbConnection conn, String tableName) {
        String sql = "SELECT COUNT(*) FROM " + safeTableName(conn,tableName);
        return executeQuery(conn, sql, rs -> {
            rs.next();
            return rs.getInt(1);
        });
    }

    /**
     * 将 JDBC 返回的日期/时间对象转为字符串，避免 Jackson 序列化为时间戳数字
     * DM8 和 MySQL/Doris 的 DATE/TIMESTAMP/DATETIME 全覆盖
     */
    private static Object convertValueForJson(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.sql.Timestamp) value);
        }
        if (value instanceof java.sql.Date) {
            return new SimpleDateFormat("yyyy-MM-dd").format((java.sql.Date) value);
        }
        if (value instanceof java.sql.Time) {
            return new SimpleDateFormat("HH:mm:ss").format((java.sql.Time) value);
        }
        if (value instanceof java.util.Date) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format((java.util.Date) value);
        }
        // Doris/DM8 via MySQL Connector/J 8.x 可能返回 java.time 类型
        if (value instanceof LocalDateTime) {
            return ((LocalDateTime) value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof LocalDate) {
            return ((LocalDate) value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        }
        if (value instanceof LocalTime) {
            return ((LocalTime) value).format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        }
        if (value instanceof OffsetDateTime) {
            return ((OffsetDateTime) value).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return value;
    }

    /**
     * 将 JDBC SQL 类型名映射为前端类型 (string / number / boolean / date)
     * DM8 + MySQL/Doris 全覆盖
     */
    public static String mapSqlTypeToFrontendType(String sqlTypeName) {
        if (sqlTypeName == null) return "string";
        String upper = sqlTypeName.toUpperCase().trim();

        // 日期/时间类型 → date
        // DM8: DATE, TIME, TIMESTAMP, DATETIME, DATETIME WITH TIME ZONE
        // MySQL/Doris: DATE, DATETIME, TIMESTAMP, TIME, YEAR
        // 通用: 凡是包含 DATE、TIME、TIMESTAMP、YEAR 的类型都归为 date
        if (upper.contains("DATE") || upper.contains("TIME")
            || upper.contains("TIMESTAMP") || upper.equals("YEAR")) {
            return "date";
        }

        // 数值类型 → number
        if (upper.contains("INT") || upper.contains("FLOAT") || upper.contains("DOUBLE")
            || upper.contains("DECIMAL") || upper.contains("NUMERIC") || upper.contains("NUMBER")
            || upper.contains("REAL") || upper.contains("BIGINT") || upper.contains("SMALLINT")
            || upper.contains("TINYINT") || upper.contains("MONEY") || upper.contains("SERIAL")) {
            return "number";
        }

        // 布尔类型 → boolean
        if (upper.contains("BOOL") || upper.equals("BIT")) {
            return "boolean";
        }

        // 默认 → string
        return "string";
    }

    /**
     * 从 ResultSetMetaData 提取列元数据（含字段注释）
     * JDBC 的 getColumns() 方法在 DM8/MySQL/Doris 下都能获取 REMARKS
     * 注意：MySQL 需要连接参数 useInformationSchema=true 才能获取注释
     */
    private List<Map<String, String>> extractColumnMetadata(ResultSetMetaData meta, Connection conn,
                                                             String catalog, String schema, String tableName) throws SQLException {
        // 先从 DatabaseMetaData.getColumns() 获取字段注释
        Map<String, String> commentMap = new LinkedHashMap<>();
        try {
            DatabaseMetaData dbMeta = conn.getMetaData();
            try (ResultSet colRs = dbMeta.getColumns(catalog, schema, tableName, "%")) {
                while (colRs.next()) {
                    String colName = colRs.getString("COLUMN_NAME");
                    String remarks = colRs.getString("REMARKS");
                    if (remarks != null && !remarks.isEmpty()) {
                        commentMap.put(colName, remarks);
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("获取列注释失败 (可能是驱动不支持): {}", e.getMessage());
        }

        List<Map<String, String>> columns = new ArrayList<>();
        int colCount = meta.getColumnCount();
        for (int i = 1; i <= colCount; i++) {
            Map<String, String> col = new LinkedHashMap<>();
            col.put("name", meta.getColumnName(i));
            String sqlType = meta.getColumnTypeName(i);
            col.put("type", mapSqlTypeToFrontendType(sqlType));
            col.put("sqlType", sqlType != null ? sqlType : "UNKNOWN");
            col.put("comment", commentMap.getOrDefault(meta.getColumnName(i), ""));
            columns.add(col);
        }
        return columns;
    }

    /** 保持旧签名兼容（无 comment 场景） */
    private List<Map<String, String>> extractColumnMetadata(ResultSetMetaData meta) throws SQLException {
        List<Map<String, String>> columns = new ArrayList<>();
        int colCount = meta.getColumnCount();
        for (int i = 1; i <= colCount; i++) {
            Map<String, String> col = new LinkedHashMap<>();
            col.put("name", meta.getColumnName(i));
            String sqlType = meta.getColumnTypeName(i);
            col.put("type", mapSqlTypeToFrontendType(sqlType));
            col.put("sqlType", sqlType != null ? sqlType : "UNKNOWN");
            col.put("comment", "");
            columns.add(col);
        }
        return columns;
    }

    /**
     * 预览表数据（前 N 行），同时返回列元数据
     */
    public List<Map<String, Object>> previewTable(DbConnection conn, String tableName, int limit) {
        String sql = "SELECT * FROM " + safeTableName(conn,tableName) + " LIMIT " + limit;
        return executeQuery(conn, sql, rs -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), convertValueForJson(rs.getObject(i)));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    /**
     * 预览表数据（前 N 行），同时返回列元数据（含字段注释）
     */
    public TableDataResult previewTableWithColumns(DbConnection conn, String tableName, int limit) {
        String sql = "SELECT * FROM " + safeTableName(conn,tableName) + " LIMIT " + limit;
        return executeQueryWithColumns(conn, sql, tableName);
    }

    /**
     * 查询全表数据，同时返回列元数据（含字段注释）
     */
    public TableDataResult queryTableWithColumns(DbConnection conn, String tableName) {
        String sql = "SELECT * FROM " + safeTableName(conn,tableName);
        return executeQueryWithColumns(conn, sql, tableName);
    }

    /**
     * 执行查询并返回带注释的列元数据 + 数据行
     */
    private TableDataResult executeQueryWithColumns(DbConnection conn, String sql, String tableName) {
        String url = buildJdbcUrl(conn);
        String driver = getDriverClass(conn.getDbType());
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + driver, e);
        }
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword())) {
            // 1. 获取字段注释
            Map<String, String> commentMap = new LinkedHashMap<>();
            try {
                String catalog = c.getCatalog();
                String schema = conn.getSchemaName();
                if (schema == null || schema.isEmpty()) schema = conn.getUsername();
                if ("Dameng".equalsIgnoreCase(conn.getDbType())) {
                    schema = conn.getSchemaName();
                }
                DatabaseMetaData dbMeta = c.getMetaData();
                try (ResultSet colRs = dbMeta.getColumns(catalog, schema, tableName, "%")) {
                    while (colRs.next()) {
                        String colName = colRs.getString("COLUMN_NAME");
                        String remarks = colRs.getString("REMARKS");
                        if (remarks != null && !remarks.isEmpty()) {
                            commentMap.put(colName, remarks);
                        }
                    }
                }
            } catch (SQLException e) {
                log.warn("获取列注释失败: {}", e.getMessage());
            }

            // 2. 查询数据
            try (Statement stmt = c.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();

                List<Map<String, String>> columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    Map<String, String> col = new LinkedHashMap<>();
                    col.put("name", meta.getColumnName(i));
                    String sqlType = meta.getColumnTypeName(i);
                    col.put("type", mapSqlTypeToFrontendType(sqlType));
                    col.put("sqlType", sqlType != null ? sqlType : "UNKNOWN");
                    col.put("comment", commentMap.getOrDefault(meta.getColumnName(i), ""));
                    columns.add(col);
                }

                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= colCount; i++) {
                        row.put(meta.getColumnName(i), convertValueForJson(rs.getObject(i)));
                    }
                    rows.add(row);
                }
                return new TableDataResult(rows, columns);
            }
        } catch (SQLException e) {
            log.error("数据库查询失败: {}", e.getMessage());
            throw new RuntimeException("数据库查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询全表数据（仅返回数据行，兼容旧接口）
     */
    public List<Map<String, Object>> queryTable(DbConnection conn, String tableName) {
        String sql = "SELECT * FROM " + safeTableName(conn,tableName);
        return executeQuery(conn, sql, rs -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    row.put(meta.getColumnName(i), convertValueForJson(rs.getObject(i)));
                }
                rows.add(row);
            }
            return rows;
        });
    }

    /**
     * 表数据查询结果：数据行 + 列元数据
     */
    public static class TableDataResult {
        private final List<Map<String, Object>> rows;
        private final List<Map<String, String>> columns;

        public TableDataResult(List<Map<String, Object>> rows, List<Map<String, String>> columns) {
            this.rows = rows;
            this.columns = columns;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public List<Map<String, String>> getColumns() { return columns; }
    }

    // ==================== 工具方法 ====================

    /**
     * 构建安全表名（达梦需要 SCHEMA.TABLE 格式，MySQL 直接用表名）
     */
    private String safeTableName(DbConnection conn, String tableName) {
        if (!tableName.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("非法表名: " + tableName);
        }
        if ("Dameng".equalsIgnoreCase(conn.getDbType())) {
            // 达梦：双引号保留大小写
            String schema = conn.getSchemaName();
            if (schema != null && !schema.isEmpty()) {
                return "\"" + schema + "\".\"" + tableName + "\"";
            }
            return "\"" + tableName + "\"";
        }
        // MySQL/Doris：反引号保留大小写
        return "`" + tableName + "`";
    }

    @FunctionalInterface
    public interface ResultSetExtractor<T> {
        T extract(ResultSet rs) throws SQLException;
    }

    <T> T executeQuery(DbConnection conn, String sql, ResultSetExtractor<T> extractor) {
        String url = buildJdbcUrl(conn);
        String driver = getDriverClass(conn.getDbType());
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + driver, e);
        }
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword());
             Statement stmt = c.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return extractor.extract(rs);
        } catch (SQLException e) {
            log.error("数据库查询失败: {}", e.getMessage());
            throw new RuntimeException("数据库查询失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取完整的连接信息（含密码），供内部查询使用
     */
    public DbConnection getFullConnection(String connId) {
        return repository.findById(connId);
    }
}
