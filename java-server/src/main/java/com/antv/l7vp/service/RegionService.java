package com.antv.l7vp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 语义区域字典（智能体技能 N2 / 功能 05）：区域名 → bbox / 中心 / 缩放级。
 *
 * <p>数据来自 classpath 的 regions.json（见 doc/AGENT_MAP_CONTROL_SKILL.md §7）。
 * 字典保证常用区域的<b>准确</b>边界，查不到时由 LLM 用它自己的地理常识给 bbox
 * 走 {@code map.focus} 的 bounds 模式兜底——两者结合才覆盖得住开放的区域集合。
 *
 * <p>只读，进程内缓存；文件缺失/损坏只告警不阻断启动（退化成「字典为空」，功能 05 仍可走 LLM 兜底）。
 */
@Service
public class RegionService {

    private static final Logger log = LoggerFactory.getLogger(RegionService.class);

    private static final String RESOURCE = "regions.json";

    private final List<Map<String, Object>> regions = new ArrayList<>();

    /** 单构造器由 Spring 自动注入；不用字段注入是为了让单元测试能直接 new */
    public RegionService(ObjectMapper objectMapper) {
        load(objectMapper);
    }

    @SuppressWarnings("unchecked")
    private void load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            List<Map<String, Object>> list = objectMapper.readValue(in, List.class);
            if (list != null) {
                for (Map<String, Object> region : list) {
                    if (region != null && region.get("key") != null) {
                        regions.add(region);
                    }
                }
            }
            log.info("[AGENT_REGION] loaded {} regions from {}", regions.size(), RESOURCE);
        } catch (Exception e) {
            log.warn("[AGENT_REGION] load {} failed, region dictionary disabled: {}", RESOURCE, e.getMessage());
        }
    }

    /**
     * 按区域名或别名检索。
     *
     * <p>匹配规则（§7）：name 或任一 alias 与 q 互相包含（大小写不敏感）即命中。
     * 反向包含（{@code q} 含 {@code name}）是为了容忍「渤海区域」「南海方向」这类
     * 带修饰词的问法，别名须 ≥2 字才参与反向匹配以免误命中。
     *
     * @param q 关键词；null/空 → 返回全部
     */
    public List<Map<String, Object>> search(String q) {
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return Collections.unmodifiableList(regions);
        }
        List<Map<String, Object>> hit = new ArrayList<>();
        for (Map<String, Object> region : regions) {
            if (matches(region, needle)) {
                hit.add(region);
            }
        }
        return hit;
    }

    private boolean matches(Map<String, Object> region, String needle) {
        List<String> terms = new ArrayList<>();
        Object name = region.get("name");
        if (name != null) {
            terms.add(String.valueOf(name));
        }
        Object aliases = region.get("aliases");
        if (aliases instanceof List) {
            for (Object alias : (List<?>) aliases) {
                if (alias != null) {
                    terms.add(String.valueOf(alias));
                }
            }
        }
        for (String term : terms) {
            String lower = term.toLowerCase(Locale.ROOT);
            if (lower.contains(needle)) {
                return true;
            }
            if (lower.length() >= 2 && needle.contains(lower)) {
                return true;
            }
        }
        return false;
    }

    /** 按 key 取区域（N3 的 region 模式解析用）；未命中返回 null */
    public Map<String, Object> findByKey(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String wanted = key.trim();
        for (Map<String, Object> region : regions) {
            if (wanted.equalsIgnoreCase(String.valueOf(region.get("key")))) {
                return region;
            }
        }
        return null;
    }

    /** 全部区域 key，用于 regionKey 未命中的错误提示 */
    public List<String> listKeys() {
        List<String> keys = new ArrayList<>();
        for (Map<String, Object> region : regions) {
            keys.add(String.valueOf(region.get("key")));
        }
        return keys;
    }

    /** 区域 bbox → {@code map.focus} 的 bounds 模式载荷 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toBoundsPayload(Map<String, Object> region) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "bounds");
        payload.put("bbox", region.get("bbox"));
        payload.put("regionKey", region.get("key"));
        payload.put("regionName", region.get("name"));
        return payload;
    }
}
