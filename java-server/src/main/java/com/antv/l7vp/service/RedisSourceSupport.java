package com.antv.l7vp.service;

import com.antv.l7vp.model.DbConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.GeoCoordinate;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Redis 数据源支持。
 *
 * <p>Redis 不是关系库，没有 schema / table / column 这一套，所以它和 {@link DbConnectionService} 里的
 * JDBC 路径是**两条平行的实现**，只是对外复用同一组接口形状，让前端不必为 Redis 写第二套页面：
 *
 * <ul>
 *   <li>「表列表」→ 键族（key family）：把抽样到的键按第一个 {@code :} 前的公共前缀聚成
 *       {@code aircraft:*} 这样的模式，用户看不懂几万个键名，但看得懂「哪几类键」。</li>
 *   <li>「表名」→ 键的匹配模式（glob），既可以是键族给出的 {@code aircraft:*}，也可以手写
 *       {@code ship:*} 甚至 {@code *}。</li>
 *   <li>「一行」→ 一个键（hash / JSON 字符串）或一个成员（list / set / zset）。</li>
 * </ul>
 *
 * <p>值一律从 RESP 的字符串形态尽量还原成数字/布尔，否则经纬度、航向这些列会被判成 string，
 * 前端就选不出坐标字段、也做不了数值映射。还原策略见 {@link #coerceScalar(String)}：
 * 只认「纯整数」和「纯小数」，其余保持字符串（避免把 {@code 007} 这类编号、UUID 误转）。
 */
public final class RedisSourceSupport {

    private static final Logger log = LoggerFactory.getLogger(RedisSourceSupport.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 行内附加的键名/成员列 */
    public static final String KEY_COL = "redis_key";
    public static final String VALUE_COL = "value";
    public static final String MEMBER_COL = "member";
    public static final String SCORE_COL = "score";
    public static final String INDEX_COL = "index";
    /** zset 被判定为 GEO 集合时追加的经纬度列，命名刻意对齐前端自动配对规则（lng↔lat） */
    public static final String LNG_COL = "lng";
    public static final String LAT_COL = "lat";

    /** SCAN 单批建议条数（只是 hint，Redis 不保证） */
    private static final int SCAN_COUNT = 500;
    /** 单次查询最多扫描多少个键，防止 {@code *} 打爆大实例 */
    private static final int MAX_SCANNED_KEYS = 20000;
    /** 单次查询最多取多少个键的数据 */
    private static final int MAX_KEYS_PER_QUERY = 5000;
    /** list / set / zset 单个键最多展开多少个成员 */
    private static final int MAX_MEMBERS_PER_KEY = 200;
    /** 键族抽样规模 */
    private static final int FAMILY_SAMPLE_KEYS = 2000;
    /** 判定键族类型时最多 TYPE 探测几个键 */
    private static final int TYPE_PROBE = 5;
    /** 列数上限 */
    private static final int MAX_COLUMNS = 200;

    /** 键模式白名单：Redis glob 的元字符 + 常见键名字符；顺带挡住空格/引号等易出错输入 */
    private static final Pattern PATTERN_OK = Pattern.compile("^[A-Za-z0-9_:.\\-*?\\[\\]]{1,200}$");
    private static final Pattern INT_LIKE = Pattern.compile("^-?\\d{1,18}$");
    private static final Pattern DECIMAL_LIKE = Pattern.compile("^-?\\d{1,15}\\.\\d{1,10}$");

    private RedisSourceSupport() {
    }

    // ==================== 连接 ====================

    /**
     * 建立连接。schemaName 对 Redis 而言是**库序号**（0-15），留空/非数字按 0。
     */
    private static Jedis connect(DbConnection conn) {
        String host = conn.getHost();
        if (host == null || host.trim().isEmpty()) {
            throw new RuntimeException("Redis 主机地址为空");
        }
        int port = conn.getPort() > 0 ? conn.getPort() : 6379;

        int database = 0;
        String schema = conn.getSchemaName();
        if (schema != null && !schema.trim().isEmpty()) {
            try {
                database = Integer.parseInt(schema.trim());
            } catch (NumberFormatException e) {
                throw new RuntimeException("Redis 的「库序号」必须是数字(0-15)，当前值: " + schema);
            }
            if (database < 0) {
                throw new RuntimeException("Redis 的「库序号」不能为负数: " + database);
            }
        }

        String password = conn.getPassword();
        if (password != null && password.isEmpty()) {
            password = null;
        }
        String user = conn.getUsername();
        if (user != null && user.trim().isEmpty()) {
            user = null;
        }

        JedisClientConfig config = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(5000)
            .socketTimeoutMillis(20000)
            .database(database)
            .user(user)
            .password(password)
            .build();
        return new Jedis(new HostAndPort(host.trim(), port), config);
    }

    /** PING 探活 */
    public static void test(DbConnection conn) {
        try (Jedis jedis = connect(conn)) {
            String pong = jedis.ping();
            if (pong == null || !"PONG".equalsIgnoreCase(pong.trim())) {
                throw new RuntimeException("Redis PING 返回异常: " + pong);
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Redis 连接失败 (" + target(conn) + "): " + rootMessage(e), e);
        }
    }

    /**
     * 报错时把目标地址带上。Jedis 连不上时只会甩一句
     * 「Failed to connect to any host resolved for DNS name.」，对着一个 IP 看这句完全无从下手，
     * 所以这里补上 host:port/db，并附带一句「服务是否在跑」的提示。
     */
    private static String target(DbConnection conn) {
        int port = conn.getPort() > 0 ? conn.getPort() : 6379;
        String schema = conn.getSchemaName();
        String db = (schema == null || schema.trim().isEmpty()) ? "0" : schema.trim();
        return conn.getHost() + ":" + port + " db=" + db;
    }

    // ==================== 键族（当作「表列表」） ====================

    /**
     * 抽样扫描键空间，按第一个 {@code :} 切出公共前缀聚成键族。
     * 返回结构与 JDBC 的 listTables 一致：{@code [{name, comment, ...}]}。
     */
    public static List<Map<String, String>> listKeyFamilies(DbConnection conn) {
        List<Map<String, String>> families = new ArrayList<>();
        try (Jedis jedis = connect(conn)) {
            List<String> sample = sampleKeys(jedis, "*", FAMILY_SAMPLE_KEYS);

            Map<String, List<String>> groups = new LinkedHashMap<>();
            for (String key : sample) {
                groups.computeIfAbsent(prefixOf(key), k -> new ArrayList<>()).add(key);
            }

            for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
                String prefix = entry.getKey();
                List<String> keys = entry.getValue();
                String pattern = prefix.isEmpty() ? "*" : prefix + ":*";
                String type = dominantType(jedis, keys);
                Map<String, String> item = new LinkedHashMap<>();
                item.put("name", pattern);
                item.put("comment", keys.size() + " 个键 · " + typeCn(type)
                    + "（抽样 " + Math.min(keys.size(), TYPE_PROBE) + " 个判定）");
                item.put("keyType", type);
                item.put("keyCount", String.valueOf(keys.size()));
                families.add(item);
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Redis 键扫描失败 (" + target(conn) + "): " + rootMessage(e), e);
        }
        // 键多的排前面，键数相同的按名字排，避免每次刷新顺序乱跳
        families.sort((a, b) -> {
            int cmp = Integer.compare(parseIntSafe(b.get("keyCount")), parseIntSafe(a.get("keyCount")));
            return cmp != 0 ? cmp : a.get("name").compareToIgnoreCase(b.get("name"));
        });

        if (families.isEmpty()) {
            Map<String, String> empty = new LinkedHashMap<>();
            empty.put("name", "*");
            empty.put("comment", "当前库没有键（或库序号选错了）");
            empty.put("keyType", "none");
            empty.put("keyCount", "0");
            families.add(empty);
        }
        return families;
    }

    /**
     * 抽样判定这一族键的主要类型。键族里混类型（比如同前缀既有 hash 又有 string）时取出现最多的那个，
     * 只用于列表里的提示文案，不参与取数逻辑 —— 取数是逐键按真实 TYPE 分派的。
     */
    private static String dominantType(Jedis jedis, List<String> keys) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        int sampled = 0;
        for (String key : keys) {
            if (sampled++ >= TYPE_PROBE) break;
            try {
                String t = jedis.type(key);
                if (t != null) counter.merge(t, 1, Integer::sum);
            } catch (RuntimeException e) {
                log.debug("TYPE 探测失败 key={}: {}", key, e.getMessage());
            }
        }
        return counter.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse("unknown");
    }

    private static String typeCn(String type) {
        switch (type == null ? "" : type) {
            case "string": return "字符串";
            case "hash": return "哈希";
            case "list": return "列表";
            case "set": return "集合";
            case "zset": return "有序集合";
            case "stream": return "流";
            case "none": return "空";
            default: return type;
        }
    }

    /** 取第一个冒号前的部分作为族前缀；没有冒号则归到 {@code ""}（即 {@code *}） */
    private static String prefixOf(String key) {
        int idx = key.indexOf(':');
        return idx > 0 ? key.substring(0, idx) : "";
    }

    // ==================== 数据查询（当作「预览 / 全量」） ====================

    /** 查询结果：行 + 列 + 是否被截断 */
    public static final class RedisResult {
        private final List<Map<String, Object>> rows;
        private final List<Map<String, String>> columns;
        private final boolean truncated;

        RedisResult(List<Map<String, Object>> rows, List<Map<String, String>> columns, boolean truncated) {
            this.rows = rows;
            this.columns = columns;
            this.truncated = truncated;
        }

        public List<Map<String, Object>> getRows() { return rows; }
        public List<Map<String, String>> getColumns() { return columns; }
        public boolean isTruncated() { return truncated; }
    }

    /**
     * 按键模式查询数据。limit<=0 表示不额外限制（仍受 {@link #MAX_KEYS_PER_QUERY} 约束）。
     */
    public static RedisResult query(DbConnection conn, String pattern, int limit) {
        String safePattern = normalizePattern(pattern);
        int keyBudget = limit > 0 ? Math.min(limit, MAX_KEYS_PER_QUERY) : MAX_KEYS_PER_QUERY;

        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;

        try (Jedis jedis = connect(conn)) {
            List<String> keys = sampleKeys(jedis, safePattern, keyBudget);
            if (keys.size() >= keyBudget) truncated = true;

            for (String key : keys) {
                if (rows.size() >= MAX_KEYS_PER_QUERY) { truncated = true; break; }
                int before = rows.size();
                try {
                    readKey(jedis, key, rows);
                } catch (RuntimeException e) {
                    log.warn("读取 Redis 键失败，已跳过 key={}: {}", key, e.getMessage());
                    rows.subList(before, rows.size()).clear();
                }
            }
        } catch (RuntimeException e) {
            throw new RuntimeException("Redis 查询失败 (" + target(conn) + "): " + rootMessage(e), e);
        }

        return new RedisResult(rows, buildColumns(rows), truncated);
    }

    /** 统计匹配键数（用于预览时显示「共 N 个键」） */
    public static int countKeys(DbConnection conn, String pattern) {
        String safePattern = normalizePattern(pattern);
        try (Jedis jedis = connect(conn)) {
            return sampleKeys(jedis, safePattern, MAX_SCANNED_KEYS).size();
        } catch (RuntimeException e) {
            throw new RuntimeException("Redis 统计失败 (" + target(conn) + "): " + rootMessage(e), e);
        }
    }

    /**
     * SCAN 取样。用 SCAN 而不是 KEYS —— KEYS 在大实例上会阻塞 Redis 主线程。
     * 到达 max 或游标归零即停。返回顺序不保证，但会去重。
     */
    private static List<String> sampleKeys(Jedis jedis, String pattern, int max) {
        List<String> keys = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        ScanParams params = new ScanParams().match(pattern).count(SCAN_COUNT);
        String cursor = ScanParams.SCAN_POINTER_START;
        int scanned = 0;
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            for (String key : result.getResult()) {
                if (seen.add(key)) keys.add(key);
                if (keys.size() >= max) return keys;
            }
            cursor = result.getCursor();
            scanned += result.getResult().size();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor) && scanned < MAX_SCANNED_KEYS);
        return keys;
    }

    /** 把一个键展开成一行或多行 */
    private static void readKey(Jedis jedis, String key, List<Map<String, Object>> rows) {
        String type = jedis.type(key);
        if (type == null) return;
        switch (type) {
            case "hash": {
                Map<String, String> hash = jedis.hgetAll(key);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(KEY_COL, key);
                for (Map.Entry<String, String> f : hash.entrySet()) {
                    row.put(f.getKey(), coerceScalar(f.getValue()));
                }
                rows.add(row);
                break;
            }
            case "string": {
                String value = jedis.get(key);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put(KEY_COL, key);
                Map<String, Object> parsed = tryParseJsonObject(value);
                if (parsed != null) {
                    row.putAll(parsed);
                } else {
                    row.put(VALUE_COL, coerceScalar(value));
                }
                rows.add(row);
                break;
            }
            case "list": {
                List<String> values = jedis.lrange(key, 0, MAX_MEMBERS_PER_KEY - 1);
                for (int i = 0; i < values.size(); i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(KEY_COL, key);
                    row.put(INDEX_COL, i);
                    row.put(VALUE_COL, coerceScalar(values.get(i)));
                    rows.add(row);
                }
                break;
            }
            case "set": {
                Set<String> members = jedis.smembers(key);
                int i = 0;
                for (String member : members) {
                    if (i++ >= MAX_MEMBERS_PER_KEY) break;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(KEY_COL, key);
                    row.put(MEMBER_COL, coerceScalar(member));
                    rows.add(row);
                }
                break;
            }
            case "zset": {
                List<Tuple> tuples = jedis.zrangeWithScores(key, 0, MAX_MEMBERS_PER_KEY - 1);
                List<GeoCoordinate> coords = tryGeoPos(jedis, key, tuples);
                for (int i = 0; i < tuples.size(); i++) {
                    Tuple t = tuples.get(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put(KEY_COL, key);
                    row.put(MEMBER_COL, coerceScalar(t.getElement()));
                    row.put(SCORE_COL, t.getScore());
                    if (coords != null && i < coords.size()) {
                        GeoCoordinate c = coords.get(i);
                        if (c != null) {
                            row.put(LNG_COL, c.getLongitude());
                            row.put(LAT_COL, c.getLatitude());
                        }
                    }
                    rows.add(row);
                }
                break;
            }
            default:
                // stream / none 等暂不展开：地图场景用不上
                log.debug("跳过不支持的 Redis 类型 {} key={}", type, key);
        }
    }

    /**
     * 有序集合可能其实是 GEO 集合（GEO 是建在 zset 上的）。对前若干个成员试 GEOPOS，
     * 全部返回空就按普通 zset 处理。失败静默降级，不影响主流程。
     */
    private static List<GeoCoordinate> tryGeoPos(Jedis jedis, String key, List<Tuple> tuples) {
        if (tuples.isEmpty()) return null;
        int probe = Math.min(tuples.size(), 5);
        try {
            String[] members = new String[probe];
            for (int i = 0; i < probe; i++) members[i] = tuples.get(i).getElement();
            List<GeoCoordinate> head = jedis.geopos(key, members);
            boolean anyNonNull = head != null && head.stream().anyMatch(c -> c != null);
            if (!anyNonNull) return null;
            if (tuples.size() == probe) return head;

            String[] all = new String[tuples.size()];
            for (int i = 0; i < tuples.size(); i++) all[i] = tuples.get(i).getElement();
            return jedis.geopos(key, all);
        } catch (RuntimeException e) {
            log.debug("GEOPOS 探测失败(key={})，按普通有序集合处理: {}", key, e.getMessage());
            return null;
        }
    }

    // ==================== 列推导 ====================

    /**
     * 按「首次出现顺序」求所有行的键并集作为列。
     * 不直接把每个键的字段当成固定列，是因为 Redis 的 hash 字段经常各键不一致。
     */
    private static List<Map<String, String>> buildColumns(List<Map<String, Object>> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            for (String name : row.keySet()) {
                names.add(name);
                if (names.size() >= MAX_COLUMNS) break;
            }
            if (names.size() >= MAX_COLUMNS) break;
        }

        List<Map<String, String>> columns = new ArrayList<>();
        for (String name : names) {
            Map<String, String> col = new LinkedHashMap<>();
            col.put("name", name);
            String type = "string";
            for (Map<String, Object> row : rows) {
                Object v = row.get(name);
                if (v != null) { type = typeOf(v); break; }
            }
            col.put("type", type);
            col.put("sqlType", "REDIS_" + type.toUpperCase());
            col.put("comment", commentOf(name));
            columns.add(col);
        }
        return columns;
    }

    private static String commentOf(String name) {
        switch (name) {
            case KEY_COL: return "Redis 键名";
            case VALUE_COL: return "键的值";
            case MEMBER_COL: return "集合成员";
            case SCORE_COL: return "有序集合分值";
            case INDEX_COL: return "列表下标";
            case LNG_COL: return "经度 (GEO)";
            case LAT_COL: return "纬度 (GEO)";
            default: return "";
        }
    }

    private static String typeOf(Object value) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return "string";
    }

    // ==================== 值还原 / 校验 ====================

    /**
     * RESP 只给字符串，这里做最小还原：纯整数 → Long，纯小数 → Double，
     * true/false → Boolean，其余原样。
     *
     * <p><b>刻意不做的事</b>：不把 {@code "007123"} 这类带前导零的串转成数字（那多半是编号，
     * 转了就丢零；而真正的经纬度不会有前导零），也不认科学计数法 —— 少猜一点，
     * 猜错了在地图上表现为「点跑到奇怪的地方」，比类型判错更难查。
     */
    static Object coerceScalar(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return raw;
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        // 前导零的「整数」当编号处理、不转数字：mmsi / 呼号这类值转了会丢零。
        // 但**带小数点就不算编号** —— 否则 -0.25、0.5 这类合法负数/小数会被误判成字符串，
        // 表现就是经纬度列被判成 string、前端选不出坐标字段。
        String digits = v.charAt(0) == '-' ? v.substring(1) : v;
        if (digits.length() > 1 && digits.charAt(0) == '0' && digits.indexOf('.') < 0) return raw;
        if (INT_LIKE.matcher(v).matches()) {
            try {
                return Long.parseLong(v);
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        if (DECIMAL_LIKE.matcher(v).matches()) {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException ignored) {
                return raw;
            }
        }
        return raw;
    }

    /** 字符串值是 JSON 对象时展开成一行；不是对象（数组/标量/非法 JSON）返回 null 走 value 列 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> tryParseJsonObject(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.length() < 2 || v.charAt(0) != '{' || v.charAt(v.length() - 1) != '}') return null;
        try {
            Map<String, Object> parsed = MAPPER.readValue(v, Map.class);
            Map<String, Object> row = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                row.put(e.getKey(), normalizeJsonValue(e.getValue()));
            }
            return row;
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON 值原样保留数字/布尔；嵌套对象与数组序列化成字符串，避免把一行撑成全表并集列 */
    private static Object normalizeJsonValue(Object value) {
        if (value == null || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value instanceof String ? coerceScalar((String) value) : value;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    /**
     * 校验并归一化键模式。为空 → {@code *}。
     * 白名单挡住的是「用户手滑输入」和「把空格/引号带进来」这类问题，不是为了防注入 —— 
     * MATCH 的参数是模式而非命令，本来就不参与命令拼接。
     */
    static String normalizePattern(String pattern) {
        if (pattern == null || pattern.trim().isEmpty()) return "*";
        String p = pattern.trim();
        if (!PATTERN_OK.matcher(p).matches()) {
            throw new RuntimeException(
                "非法的键模式: " + p + "（只允许字母数字与 _ : . - * ? [ ] ，长度 1-200）");
        }
        return p;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        String msg = t.getMessage();
        return msg == null || msg.isEmpty() ? t.getClass().getSimpleName() : msg;
    }
}
