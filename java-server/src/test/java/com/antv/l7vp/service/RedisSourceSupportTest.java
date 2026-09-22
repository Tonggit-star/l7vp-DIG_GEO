package com.antv.l7vp.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RedisSourceSupport 的纯逻辑单测（不需要真实 Redis）。
 *
 * <p>挑这两处测是因为它们「错了也不报错、但结果很难看」：
 * <ul>
 *   <li>{@code coerceScalar}：RESP 只能给字符串，还原错了经纬度/航向列会被判成 string，
 *       前端就选不出坐标字段、也做不了数值映射，图上直接不出点；</li>
 *   <li>{@code normalizePattern}：用户手输的键模式，白名单漏了会一路传到 SCAN。</li>
 * </ul>
 */
class RedisSourceSupportTest {

    // ==================== coerceScalar ====================

    @Test
    void coerceScalar_reducesIntegers() {
        assertEquals(42L, RedisSourceSupport.coerceScalar("42"));
        assertEquals(-7L, RedisSourceSupport.coerceScalar("-7"));
        assertEquals(0L, RedisSourceSupport.coerceScalar("0"));
        assertEquals(413123456L, RedisSourceSupport.coerceScalar("413123456"));
    }

    @Test
    void coerceScalar_reducesDecimals() {
        assertEquals(120.5, RedisSourceSupport.coerceScalar("120.5"));
        assertEquals(-0.25, RedisSourceSupport.coerceScalar("-0.25"));
        assertEquals(0.5, RedisSourceSupport.coerceScalar("0.5"));
        assertEquals(-179.99999, RedisSourceSupport.coerceScalar("-179.99999"));
    }

    @Test
    void coerceScalar_reducesBooleans() {
        assertEquals(Boolean.TRUE, RedisSourceSupport.coerceScalar("true"));
        assertEquals(Boolean.TRUE, RedisSourceSupport.coerceScalar("TRUE"));
        assertEquals(Boolean.FALSE, RedisSourceSupport.coerceScalar("false"));
    }

    /** 前导零的整数按编号处理：转了会丢零，而真经纬度不会有前导零 */
    @Test
    void coerceScalar_keepsLeadingZeroIntegersAsString() {
        assertEquals("007123", RedisSourceSupport.coerceScalar("007123"));
        assertEquals("-007", RedisSourceSupport.coerceScalar("-007"));
    }

    @Test
    void coerceScalar_keepsNonNumericAsString() {
        assertEquals("abc", RedisSourceSupport.coerceScalar("abc"));
        assertEquals("12abc", RedisSourceSupport.coerceScalar("12abc"));
        assertEquals("1e5", RedisSourceSupport.coerceScalar("1e5"));   // 不认科学计数法
        assertEquals("abc-def", RedisSourceSupport.coerceScalar("abc-def"));
        assertNull(RedisSourceSupport.coerceScalar(null));
    }

    /** 哈希/字符串值常带空白，trim 后应能正常还原 */
    @Test
    void coerceScalar_trimsWhitespace() {
        assertEquals(118.7, RedisSourceSupport.coerceScalar(" 118.7 "));
        assertEquals(31L, RedisSourceSupport.coerceScalar("31 "));
    }

    // ==================== normalizePattern ====================

    @Test
    void normalizePattern_defaultsToMatchAll() {
        assertEquals("*", RedisSourceSupport.normalizePattern(null));
        assertEquals("*", RedisSourceSupport.normalizePattern(""));
        assertEquals("*", RedisSourceSupport.normalizePattern("   "));
    }

    @Test
    void normalizePattern_acceptsGlobForms() {
        assertEquals("aircraft:*", RedisSourceSupport.normalizePattern("aircraft:*"));
        assertEquals("a:b:*", RedisSourceSupport.normalizePattern(" a:b:* "));
        assertEquals("k?_1", RedisSourceSupport.normalizePattern("k?_1"));
        assertEquals("[abc]*", RedisSourceSupport.normalizePattern("[abc]*"));
        assertEquals("ship-1.2", RedisSourceSupport.normalizePattern("ship-1.2"));
        assertEquals("_internal", RedisSourceSupport.normalizePattern("_internal"));
    }

    @Test
    void normalizePattern_rejectsBadInput() {
        assertThrows(RuntimeException.class, () -> RedisSourceSupport.normalizePattern("a b"));
        assertThrows(RuntimeException.class, () -> RedisSourceSupport.normalizePattern("a\"b"));
        assertThrows(RuntimeException.class, () -> RedisSourceSupport.normalizePattern("a\nb"));
        assertThrows(RuntimeException.class, () -> RedisSourceSupport.normalizePattern("a;b"));
        assertThrows(RuntimeException.class,
            () -> RedisSourceSupport.normalizePattern(new String(new char[201]).replace('\0', 'x')));
    }

    // ==================== 数据源类型（DbConnectionService） ====================

    @Test
    void normalizeDbType_acceptsAliases() {
        assertEquals(DbConnectionService.TYPE_POSTGRESQL, DbConnectionService.normalizeDbType("postgres"));
        assertEquals(DbConnectionService.TYPE_POSTGRESQL, DbConnectionService.normalizeDbType("pg"));
        assertEquals(DbConnectionService.TYPE_POSTGRESQL, DbConnectionService.normalizeDbType("PostgreSQL"));
        assertEquals(DbConnectionService.TYPE_MYSQL, DbConnectionService.normalizeDbType("mysql"));
        assertEquals(DbConnectionService.TYPE_MYSQL, DbConnectionService.normalizeDbType("MariaDB"));
        assertEquals(DbConnectionService.TYPE_DAMENG, DbConnectionService.normalizeDbType("dm8"));
        assertEquals(DbConnectionService.TYPE_REDIS, DbConnectionService.normalizeDbType("redis"));
        assertEquals(DbConnectionService.TYPE_DORIS, DbConnectionService.normalizeDbType("Doris"));
    }

    @Test
    void normalizeDbType_rejectsUnknown() {
        RuntimeException e = assertThrows(RuntimeException.class,
            () -> DbConnectionService.normalizeDbType("Oracle"));
        // 报错信息要能直接指导用户，把可选项列出来
        assertEquals(true, e.getMessage().contains("PostgreSQL"));
        assertEquals(true, e.getMessage().contains("Redis"));
    }

    /** 五种类型都要在 supported-types 里露面，前端下拉直接吃这份数据 */
    @Test
    void supportedTypes_coversAllFive() {
        assertEquals(5, DbConnectionService.getSupportedTypes().size());
        assertEquals(5, DbConnectionService.getSupportedTypes().stream()
            .map(t -> String.valueOf(t.get("value"))).distinct().count());
    }
}
