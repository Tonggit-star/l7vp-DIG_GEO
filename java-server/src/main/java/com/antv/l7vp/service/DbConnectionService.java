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
        sanitize(conn);
        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        conn.setCreateTime(now);
        conn.setUpdateTime(now);
        return repository.insert(conn);
    }

    public DbConnection updateConnection(String id, DbConnection conn) {
        DbConnection existing = repository.findById(id);
        if (existing == null) throw new RuntimeException("连接配置不存在");
        conn.setConnId(id);
        // 页面上的密码框在编辑态留空表示「不修改」，这里必须沿用原口令 ——
        // 否则「点开连接→改个名字→保存」就会把口令清空，而界面还写着「留空不修改」。
        if (conn.getPassword() == null || conn.getPassword().isEmpty()) {
            conn.setPassword(existing.getPassword());
        }
        sanitize(conn);
        conn.setUpdateTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return repository.update(conn);
    }

    /**
     * 落库前的统一校验与补齐：类型收敛为规范名、必填项校验、
     * 并把 null 收敛成空串（DB_CONNECTIONS 的 USERNAME / PASSWORD 是 NOT NULL）。
     */
    private void sanitize(DbConnection conn) {
        if (conn.getConnName() == null || conn.getConnName().trim().isEmpty()) {
            throw new RuntimeException("连接名称不能为空");
        }
        conn.setConnName(conn.getConnName().trim());

        String type = normalizeDbType(conn.getDbType());
        conn.setDbType(type);

        if (conn.getHost() == null || conn.getHost().trim().isEmpty()) {
            throw new RuntimeException("主机地址不能为空");
        }
        conn.setHost(conn.getHost().trim());

        if (conn.getPort() <= 0 || conn.getPort() > 65535) {
            throw new RuntimeException("端口必须在 1-65535 之间，当前值: " + conn.getPort());
        }

        if (conn.getUsername() == null) conn.setUsername("");
        if (conn.getPassword() == null) conn.setPassword("");

        if (!isRedis(type) && conn.getUsername().trim().isEmpty()) {
            throw new RuntimeException(type + " 数据源必须填写用户名");
        }
        if (!isRedis(type)
            && (conn.getSchemaName() == null || conn.getSchemaName().trim().isEmpty())) {
            throw new RuntimeException(type + " 数据源必须填写"
                + (TYPE_DAMENG.equals(type) || TYPE_POSTGRESQL.equals(type) ? "模式名/库名" : "数据库名"));
        }
        if (conn.getSchemaName() != null) {
            conn.setSchemaName(conn.getSchemaName().trim());
        }
    }

    public void deleteConnection(String id) {
        repository.deleteById(id);
    }

    // ==================== 数据源类型 ====================

    public static final String TYPE_DAMENG = "Dameng";
    public static final String TYPE_DORIS = "Doris";
    public static final String TYPE_MYSQL = "MySQL";
    public static final String TYPE_POSTGRESQL = "PostgreSQL";
    public static final String TYPE_REDIS = "Redis";

    /**
     * 支持的数据源类型。前端下拉框直接消费这份数据
     * （{@code GET /api/db-connections/supported-types}）——默认端口、字段叫法、操作提示
     * 只在这里写一遍，省得前后端各维护一份、改一边忘一边。
     */
    public static List<Map<String, Object>> getSupportedTypes() {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(dbType(TYPE_DAMENG, "达梦 DM8", 5236, "模式名 (Schema)", true,
            "国产数据库。模式名必填（如 DIG_GEO / TEST）。驱动是本地 system-scope 依赖，"
                + "须确保 java-server/lib/DmJdbcDriver8.jar 存在"));
        list.add(dbType(TYPE_DORIS, "Doris / StarRocks", 9030, "数据库名 (Database)", true,
            "走 MySQL 协议，FE 查询端口默认 9030（不是 8030）「数据库名」填 Doris 里的库名"));
        list.add(dbType(TYPE_MYSQL, "MySQL / MariaDB", 3306, "数据库名 (Database)", true,
            "「数据库名」填要连的库名。字段注释依赖驱动参数 useInformationSchema=true，本服务已内置"));
        list.add(dbType(TYPE_POSTGRESQL, "PostgreSQL", 5432, "数据库名 (Database)", true,
            "「数据库名」必填（连接串必须带库名）。表列表默认取 public 及其他非系统 schema，"
                + "非 public 的表会显示成 schema.表名"));
        list.add(dbType(TYPE_REDIS, "Redis", 6379, "库序号 (db index)", false,
            "非关系库，没有库/表/字段。「库序号」填 Redis 的 db index（0-15，留空按 0）；"
                + "建数据集时选的是「键模式」，如 aircraft:*。无鉴权时用户名与密码都留空"));
        return list;
    }

    private static Map<String, Object> dbType(String value, String label, int defaultPort,
                                              String schemaLabel, boolean relational, String hint) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        m.put("defaultPort", defaultPort);
        m.put("schemaLabel", schemaLabel);
        m.put("relational", relational);
        m.put("hint", hint);
        return m;
    }

    /** 把历史值/大小写不一的值收敛成规范类型名，认不出就报错并把可选项列出来 */
    public static String normalizeDbType(String dbType) {
        if (dbType != null) {
            String t = dbType.trim();
            if (TYPE_DAMENG.equalsIgnoreCase(t) || "dm".equalsIgnoreCase(t) || "dm8".equalsIgnoreCase(t)) return TYPE_DAMENG;
            if (TYPE_DORIS.equalsIgnoreCase(t)) return TYPE_DORIS;
            if (TYPE_MYSQL.equalsIgnoreCase(t) || "mariadb".equalsIgnoreCase(t)) return TYPE_MYSQL;
            if (TYPE_POSTGRESQL.equalsIgnoreCase(t) || "pg".equalsIgnoreCase(t)
                || "postgres".equalsIgnoreCase(t) || "postgis".equalsIgnoreCase(t)) return TYPE_POSTGRESQL;
            if (TYPE_REDIS.equalsIgnoreCase(t)) return TYPE_REDIS;
        }
        throw new RuntimeException("不支持的数据源类型: " + dbType
            + "（可选: Dameng / Doris / MySQL / PostgreSQL / Redis）");
    }

    public static boolean isRedis(String dbType) {
        return TYPE_REDIS.equalsIgnoreCase(dbType);
    }

    // ==================== 连接管理 ====================

    /** MySQL 系（MySQL / Doris）的公共连接参数 */
    private static final String MYSQL_PARAMS =
        "?useSSL=false&allowPublicKeyRetrieval=true&useInformationSchema=true&serverTimezone=Asia/Shanghai";

    private String buildJdbcUrl(DbConnection conn) {
        String type = normalizeDbType(conn.getDbType());
        String db = conn.getSchemaName() != null ? conn.getSchemaName().trim() : "";
        switch (type) {
            case TYPE_MYSQL:
            case TYPE_DORIS:
                // Doris 与 MySQL 同走 MySQL 协议，URL 形状完全一致，只是默认端口不同
                return String.format("jdbc:mysql://%s:%d/%s%s", conn.getHost(), conn.getPort(), db, MYSQL_PARAMS);
            case TYPE_POSTGRESQL:
                // PG 的连接串必须带库名；schema 是另一层概念，见 safeTableName / listTables
                return String.format("jdbc:postgresql://%s:%d/%s", conn.getHost(), conn.getPort(), db);
            case TYPE_DAMENG:
                return String.format("jdbc:dm://%s:%d/%s", conn.getHost(), conn.getPort(), db);
            case TYPE_REDIS:
                throw new RuntimeException("Redis 不是 JDBC 数据源，不能构造 JDBC URL");
            default:
                throw new RuntimeException("不支持的数据源类型: " + conn.getDbType());
        }
    }

    private String getDriverClass(String dbType) {
        switch (normalizeDbType(dbType)) {
            case TYPE_MYSQL:
            case TYPE_DORIS:
                return "com.mysql.cj.jdbc.Driver";
            case TYPE_POSTGRESQL:
                return "org.postgresql.Driver";
            case TYPE_DAMENG:
                return "dm.jdbc.driver.DmDriver";
            default:
                throw new RuntimeException("不支持的数据源类型: " + dbType);
        }
    }

    /**
     * 测试数据库连接。Redis 走 PING，其余走 JDBC SELECT 1。
     */
    public boolean testConnection(DbConnection conn) {
        if (isRedis(conn.getDbType())) {
            RedisSourceSupport.test(conn);
            return true;
        }
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
    /** PostgreSQL 里要跳过的系统/内部模式（TimescaleDB 的分片放在 _timescaledb_internal） */
    private static final Set<String> PG_SYSTEM_SCHEMAS = new HashSet<>(Arrays.asList(
        "pg_catalog", "information_schema", "pg_toast"));

    public List<Map<String, String>> listTables(DbConnection conn) {
        String type = normalizeDbType(conn.getDbType());
        // Redis 没有表，用「键族」顶替这个位置
        if (TYPE_REDIS.equals(type)) {
            return RedisSourceSupport.listKeyFamilies(conn);
        }

        String url = buildJdbcUrl(conn);
        String driver = getDriverClass(type);
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到数据库驱动: " + driver, e);
        }
        String schema = conn.getSchemaName() == null ? "" : conn.getSchemaName().trim();

        List<Map<String, String>> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, conn.getUsername(), conn.getPassword())) {
            DatabaseMetaData meta = c.getMetaData();
            if (TYPE_DAMENG.equals(type)) {
                // 达梦：schemaPattern 就是模式名；留空时交给驱动回退到当前用户
                collectTables(meta, null, schema.isEmpty() ? conn.getUsername() : schema, tables, false);
            } else if (TYPE_POSTGRESQL.equals(type)) {
                // PG：catalog 是库名、schema 是另一层。取全部 schema 再筛掉系统模式，
                // 这样数据放在非 public 模式时也能被选到，且不会把 TimescaleDB 的内部表混进来。
                collectTables(meta, null, null, tables, true);
            } else {
                // MySQL / Doris：catalog 才是库名。schemaPattern 必须传 null ——
                // 在 Connector/J 下把库名当 schemaPattern 传会查不到任何表。
                collectTables(meta, schema.isEmpty() ? null : schema, null, tables, false);
            }
        } catch (SQLException e) {
            log.error("获取表列表失败: {}", e.getMessage());
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }
        tables.sort((a, b) -> a.get("name").compareToIgnoreCase(b.get("name")));
        return tables;
    }

    /**
     * 收集表。qualifySchema=true 时（PG 路径）跳过系统模式，并把非 public 模式的表写成
     * {@code schema.表名}，与 safeTableName 的两段式解析对齐。
     */
    private void collectTables(DatabaseMetaData meta, String catalog, String schemaPattern,
                               List<Map<String, String>> out, boolean qualifySchema) throws SQLException {
        try (ResultSet rs = meta.getTables(catalog, schemaPattern, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String tableName = rs.getString("TABLE_NAME");
                String displayName = tableName;
                if (qualifySchema) {
                    String tableSchema = rs.getString("TABLE_SCHEM");
                    if (tableSchema == null) continue;
                    String lower = tableSchema.toLowerCase();
                    if (PG_SYSTEM_SCHEMAS.contains(lower)
                        || lower.startsWith("_") || lower.startsWith("timescaledb")) {
                        continue;
                    }
                    // public 下的表不加前缀（最常见场景读着干净），其余限定到 schema.表名
                    displayName = "public".equals(lower) ? tableName : tableSchema + "." + tableName;
                }
                Map<String, String> table = new LinkedHashMap<>();
                table.put("name", displayName);
                String remarks = rs.getString("REMARKS");
                table.put("comment", remarks != null ? remarks : "");
                out.add(table);
            }
        }
    }

    /**
     * 获取指定表的数据行数
     */
    public int getRowCount(DbConnection conn, String tableName) {
        if (isRedis(conn.getDbType())) {
            return RedisSourceSupport.countKeys(conn, tableName);
        }
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
        // PostgreSQL 的 jsonb / json / 数组等类型经 pgjdbc 出来是 PGobject，
        // 直接交给 Jackson 会序列化成 {"type":"jsonb","value":"…"} 这种嵌套对象，
        // 而数据集是「一行一列一个标量」的模型，嵌套对象下游不好处理，
        // 故还原成它原本的字符串（jsonb 就是那段 JSON 文本）。
        if (value instanceof org.postgresql.util.PGobject) {
            return ((org.postgresql.util.PGobject) value).getValue();
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
        if (isRedis(conn.getDbType())) {
            return RedisSourceSupport.query(conn, tableName, limit).getRows();
        }
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
        if (isRedis(conn.getDbType())) {
            RedisSourceSupport.RedisResult r = RedisSourceSupport.query(conn, tableName, limit);
            return new TableDataResult(r.getRows(), r.getColumns(), r.isTruncated());
        }
        String sql = "SELECT * FROM " + safeTableName(conn,tableName) + " LIMIT " + limit;
        return executeQueryWithColumns(conn, sql, tableName);
    }

    /**
     * 查询全表数据，同时返回列元数据（含字段注释）
     */
    public TableDataResult queryTableWithColumns(DbConnection conn, String tableName) {
        return queryTableWithColumns(conn, tableName, null, 0);
    }

    /**
     * 查询全表，支持**选列**与**行数上限**。
     *
     * <p>选列不是可选的花活，是宽表的必需品：本项目实测踩过一次——`ship_latest` 只有 4000 行，
     * 本身一点都不大，但其中一个约 4.5KB 的 jsonb 原文列（`raw_message`）把单次响应撑到 **23MB**，
     * 占总量的 95.6%；前端那条请求反复被中断，数据集预览一直是空的。
     * 建数据集时把这个列排除掉，同一个数据集只剩 **0.9MB**。
     *
     * @param columns 要查的列；null 或空 = 全列（{@code SELECT *}）
     * @param maxRows 行数上限；&lt;=0 = 不限制。实现上多取一行用来判断是否被截断
     */
    public TableDataResult queryTableWithColumns(DbConnection conn, String tableName,
                                                 List<String> columns, int maxRows) {
        if (isRedis(conn.getDbType())) {
            // limit<=0：不额外限制，由 RedisSourceSupport 的上限兜底
            RedisSourceSupport.RedisResult r = RedisSourceSupport.query(conn, tableName, 0);
            return new TableDataResult(r.getRows(), r.getColumns(), r.isTruncated());
        }
        String sql = "SELECT " + buildSelectList(conn, columns)
            + " FROM " + safeTableName(conn, tableName)
            + (maxRows > 0 ? " LIMIT " + (maxRows + 1) : "");
        TableDataResult raw = executeQueryWithColumns(conn, sql, tableName);
        if (maxRows > 0 && raw.getRows().size() > maxRows) {
            // 多取的那一行证明被截断了：丢掉它并把 truncated 置真，前端据此提示用户收窄条件
            return new TableDataResult(new ArrayList<>(raw.getRows().subList(0, maxRows)),
                raw.getColumns(), true);
        }
        return raw;
    }

    /**
     * 推导 {@code DatabaseMetaData.getColumns/getTables} 需要的 (catalog, schemaPattern)。
     *
     * <p>三种关系库的分工完全不同，混用就会「查得到表、查不到注释」：
     * <ul>
     *   <li>达梦：schema = 模式名（留空按用户名），catalog 传 null</li>
     *   <li>PostgreSQL：catalog 是**库名**，schema 默认 {@code public}；
     *       而连接的「模式名」字段在 PG 下装的是库名，**不能再拿它当 schema 用**</li>
     *   <li>MySQL / Doris：库名在 catalog 上，schemaPattern 必须传 null</li>
     * </ul>
     *
     * @return {@code [catalog, schemaPattern]}，元素可为 null
     */
    private String[] resolveCatalogSchema(Connection c, DbConnection conn, String tableName) throws SQLException {
        String type = normalizeDbType(conn.getDbType());
        String schema = conn.getSchemaName() == null ? "" : conn.getSchemaName().trim();
        String[] parts = tableName.split("\\.", -1);

        if (TYPE_DAMENG.equals(type)) {
            return new String[]{null, schema.isEmpty() ? conn.getUsername() : schema};
        }
        if (TYPE_POSTGRESQL.equals(type)) {
            // 两段式表名的前一段就是 schema；否则按 public
            return new String[]{c.getCatalog(), parts.length == 2 ? parts[0] : "public"};
        }
        return new String[]{schema.isEmpty() ? c.getCatalog() : schema, null};
    }

    /** 取表名的最后一段 —— getColumns/getTables 要的是裸表名，不能喂 "schema.table" */
    private static String bareTableName(String tableName) {
        int idx = tableName.lastIndexOf('.');
        return idx >= 0 ? tableName.substring(idx + 1) : tableName;
    }

    /**
     * 校验字段名。与表名同一条防线 —— 列名同样是拼进 SQL 的，只能放行纯标识符。
     */
    private static void validateColumnName(String name) {
        if (name == null || !name.matches("^[a-zA-Z0-9_]+$")) {
            throw new RuntimeException("非法字段名: " + name);
        }
    }

    /** 列名引号规则与表名一致：达梦 / PostgreSQL 双引号，MySQL / Doris 反引号 */
    private String quoteColumn(DbConnection conn, String name) {
        String type = normalizeDbType(conn.getDbType());
        if (TYPE_MYSQL.equals(type) || TYPE_DORIS.equals(type)) {
            return "`" + name + "`";
        }
        return "\"" + name + "\"";
    }

    /** 拼 SELECT 列表；未指定或指定为空则用 {@code *} */
    private String buildSelectList(DbConnection conn, List<String> columns) {
        if (columns == null || columns.isEmpty()) return "*";
        StringBuilder sb = new StringBuilder();
        for (String c : columns) {
            validateColumnName(c);
            if (sb.length() > 0) sb.append(", ");
            sb.append(quoteColumn(conn, c));
        }
        return sb.toString();
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
                String[] cs = resolveCatalogSchema(c, conn, tableName);
                DatabaseMetaData dbMeta = c.getMetaData();
                try (ResultSet colRs = dbMeta.getColumns(cs[0], cs[1], bareTableName(tableName), "%")) {
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
        if (isRedis(conn.getDbType())) {
            return RedisSourceSupport.query(conn, tableName, 0).getRows();
        }
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
        private final boolean truncated;

        public TableDataResult(List<Map<String, Object>> rows, List<Map<String, String>> columns) {
            this(rows, columns, false);
        }

        public TableDataResult(List<Map<String, Object>> rows, List<Map<String, String>> columns,
                               boolean truncated) {
            this.rows = rows;
            this.columns = columns;
            this.truncated = truncated;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public List<Map<String, String>> getColumns() { return columns; }
        /** Redis 路径可能因上限截断；关系库恒为 false */
        public boolean isTruncated() { return truncated; }
    }

    // ==================== 工具方法 ====================

    /**
     * 构建安全表名。
     *
     * <p>允许「前缀.表名」两段式，各段都必须是纯标识符（字母数字下划线）：
     * <ul>
     *   <li>达梦：不加前缀时用连接的「模式名」，加了就用写的那个；双引号保留大小写</li>
     *   <li>PostgreSQL：不加前缀时默认 public；双引号保留大小写（PG 未加引号的标识符会被折叠成小写，
     *       而这里拿到的是元数据里的真实名，加引号反而更准）</li>
     *   <li>MySQL / Doris：反引号；两段式的前一段是库名</li>
     * </ul>
     */
    private String safeTableName(DbConnection conn, String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            throw new RuntimeException("表名为空");
        }
        String type = normalizeDbType(conn.getDbType());
        String[] parts = tableName.split("\\.", -1);
        if (parts.length > 2 || parts.length == 0) {
            throw new RuntimeException("非法表名: " + tableName);
        }
        for (String p : parts) {
            if (!p.matches("^[a-zA-Z0-9_]+$")) {
                throw new RuntimeException("非法表名: " + tableName);
            }
        }

        if (TYPE_DAMENG.equals(type)) {
            if (parts.length == 2) return "\"" + parts[0] + "\".\"" + parts[1] + "\"";
            String schema = conn.getSchemaName();
            if (schema != null && !schema.trim().isEmpty()) {
                return "\"" + schema.trim() + "\".\"" + parts[0] + "\"";
            }
            return "\"" + parts[0] + "\"";
        }
        if (TYPE_POSTGRESQL.equals(type)) {
            if (parts.length == 2) return "\"" + parts[0] + "\".\"" + parts[1] + "\"";
            return "\"public\".\"" + parts[0] + "\"";
        }
        // MySQL / Doris
        if (parts.length == 2) return "`" + parts[0] + "`.`" + parts[1] + "`";
        return "`" + parts[0] + "`";
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
