package com.antv.l7vp.service;

import com.antv.l7vp.config.ZhongtaiConfig;
import com.antv.l7vp.dto.UserSession;
import com.antv.l7vp.model.DbConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 中台 API 调用服务
 * - 代理用户请求到中台数据 API
 * - 使用用户 SSO token（从 Session 获取），而非硬编码管理员 token
 * - 每次请求携带: Authorization Bearer {userToken}, orgId, spaceId, scopeType
 */
@Service
public class ZhongtaiApiService {

    private static final Logger log = LoggerFactory.getLogger(ZhongtaiApiService.class);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ZhongtaiConfig config;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 调用中台数据 API（使用用户 token）
     *
     * @param apiUrl         中台 API 完整地址
     * @param variableParams 变量参数
     * @param userSession    当前用户 Session（含 token + orgId）
     * @return API 返回的数据 Map
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchData(String apiUrl, Map<String, Object> variableParams,
                                          UserSession userSession) {
        return fetchData(apiUrl, variableParams, null, null, userSession);
    }

    /**
     * 调用中台数据 API（使用用户 token），支持动态 scopeType/spaceId
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchData(String apiUrl, Map<String, Object> variableParams,
                                          String scopeType, String spaceId,
                                          UserSession userSession) {
        if (userSession == null || userSession.getAccessToken() == null) {
            throw new RuntimeException("用户未登录或 token 不可用");
        }

        // 构建请求体
        Map<String, Object> dataServiceParams = new HashMap<>();
        dataServiceParams.put("variableParams", variableParams != null ? variableParams : new HashMap<>());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("sqlMode", "normal");
        requestBody.put("rowType", "map");
        requestBody.put("dataServiceParams", dataServiceParams);

        // 构建 headers — 携带用户上下文（支持动态 scopeType/spaceId）
        HttpHeaders headers = buildHeaders(userSession, scopeType, spaceId);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("调用中台数据 API: {} (user={}, orgId={})",
                apiUrl, userSession.getUsername(), userSession.getOrgId());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    apiUrl, HttpMethod.POST, entity, String.class);
            String respBody = response.getBody();
            log.info("中台 API 返回数据长度: {}", respBody != null ? respBody.length() : 0);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("中台 API 调用失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new RuntimeException("用户 token 已过期，请刷新后重试");
            }
            throw new RuntimeException("中台 API 调用失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("中台 API 调用异常", e);
            throw new RuntimeException("中台 API 调用失败: " + e.getMessage(), e);
        }
    }

    /**
     * 构建携带用户上下文的 HTTP Headers（支持动态覆盖 scopeType/spaceId）
     */
    private HttpHeaders buildHeaders(UserSession userSession) {
        return buildHeaders(userSession, null, null);
    }

    private HttpHeaders buildHeaders(UserSession userSession, String scopeType, String spaceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + userSession.getAccessToken());

        // scopeType: 前端传入 > 配置默认值
        String effectiveScopeType = (scopeType != null && !scopeType.isEmpty())
                ? scopeType : config.getApiScopeType();
        headers.set("scopeType", effectiveScopeType);

        // spaceId: 前端传入 > 配置默认值
        String effectiveSpaceId = (spaceId != null && !spaceId.isEmpty())
                ? spaceId : config.getApiSpaceId();
        if (effectiveSpaceId != null && !effectiveSpaceId.isEmpty()) {
            headers.set("spaceId", effectiveSpaceId);
        }

        if (config.getApiTenantId() != null && !config.getApiTenantId().isEmpty()) {
            headers.set("tenantId", config.getApiTenantId());
        }

        // 用户组织 ID
        if (userSession.getOrgId() != null && !userSession.getOrgId().isEmpty()) {
            headers.set("orgId", userSession.getOrgId());
        }

        return headers;
    }

    // ==================== 数据资源列表 ====================

    /**
     * 获取中台数据资源列表（数据表/API）
     * POST daasMeta/dataResource/list
     *
     * @param scopeType    User / Space
     * @param spaceId      空间ID
     * @param pageIndex    页码（从 1 开始，null 用 1）
     * @param pageSize     每页条数（null 用 20）
     * @param searchText   关键字搜索（表名/注释，可为 null）
     * @param dataSourceId 库的 dataSourceId（按库过滤，可为 null 表示不过滤）
     * @param userSession  用户 Session
     * @return 数据资源列表 JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchDataResourceList(String scopeType, String spaceId,
                                                     Integer pageIndex, Integer pageSize,
                                                     String searchText, String dataSourceId,
                                                     UserSession userSession) {
        String url = config.getResourceListUrl();

        int pIndex = (pageIndex != null && pageIndex > 0) ? pageIndex : 1;
        int pSize = (pageSize != null && pageSize > 0) ? pageSize : 20;

        // 构建请求体
        Map<String, Object> extMap = new LinkedHashMap<>();
        extMap.put("rdbResourceListType", "manageDevelopmentList");

        Map<String, Object> pageParam = new LinkedHashMap<>();
        pageParam.put("pageIndex", pIndex);
        pageParam.put("limit", pSize);
        pageParam.put("sortField", "lastUpdateTime");
        pageParam.put("sortType", "desc");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        // 使用传入的 spaceId，没有则用配置兜底；仍为空则不传 daas_space_id（让中台用当前上下文）
        String effectiveSpaceId = (spaceId != null && !spaceId.isEmpty())
                ? spaceId : config.getApiSpaceId();
        if (effectiveSpaceId != null && !effectiveSpaceId.isEmpty()) {
            requestBody.put("daas_space_id", effectiveSpaceId);
        }
        requestBody.put("pageParam", pageParam);
        requestBody.put("listType", "owner");
        requestBody.put("status", Arrays.asList("materialization"));
        requestBody.put("storageLayer", Arrays.asList("ODS", "DWD", "DWS", "ADS", "BFK", "WTK", "OTHER"));
        requestBody.put("publishStatus", "unpublished");
        requestBody.put("extMap", extMap);

        // 按库过滤（dataSourceId 数组，配合 dsId）。中台表清单接口用 dataSourceId 区分库
        if (dataSourceId != null && !dataSourceId.trim().isEmpty()) {
            String dsId = dataSourceId.trim();
            requestBody.put("dataSourceId", Collections.singletonList(dsId));
            requestBody.put("dsId", dsId);
        }

        // 关键字搜索（表名/注释）
        if (searchText != null && !searchText.trim().isEmpty()) {
            requestBody.put("searchText", searchText.trim());
        }

        HttpHeaders headers = buildHeaders(userSession, scopeType, spaceId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("获取中台数据资源列表: {} (scopeType={}, spaceId={}, dataSourceId={}, page={}/{}, search={}, user={})",
                url, headers.get("scopeType"), effectiveSpaceId, dataSourceId, pIndex, pSize,
                searchText, userSession.getUsername());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            String respBody = response.getBody();
            log.info("中台数据资源列表返回长度: {}", respBody != null ? respBody.length() : 0);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("获取中台数据资源列表失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("获取中台数据资源列表失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("获取中台数据资源列表异常", e);
            throw new RuntimeException("获取中台数据资源列表失败: " + e.getMessage(), e);
        }
    }

    // ==================== 库列表（应用下的库/实例） ====================

    /**
     * 获取中台「库列表」（Doris 多库 / 其它引擎库）
     * POST daasECS/unit/instance/list
     *
     * 注意：appId 会限制只查某个实例/应用下的库，默认不传（中台返回全部有权限的库/实例）。
     * 仅当调用方显式传入 appId 时才按实例过滤。
     *
     * @param appId       应用ID（可选，为 null 则不传）
     * @param scopeType   User / Space
     * @param spaceId     空间ID
     * @param userSession 用户 Session
     * @return 库/实例列表 JSON（details.data 内为 instanceId/instanceName/dbname 等）
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchDatabaseList(String appId, String scopeType, String spaceId,
                                                 UserSession userSession) {
        String url = config.getUnitInstanceUrl();

        String effectiveAppId = (appId != null && !appId.trim().isEmpty())
                ? appId.trim() : null;

        Map<String, Object> pageParam = new LinkedHashMap<>();
        pageParam.put("pageIndex", 1);
        pageParam.put("limit", 200);
        pageParam.put("sortField", "lastUpdateTime");
        pageParam.put("sortType", "desc");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        // appId 会限制只查某个实例下的库，默认不传；仅当调用方显式传入时才按实例过滤
        if (effectiveAppId != null) {
            requestBody.put("appId", effectiveAppId);
        }
        requestBody.put("accountId", null);
        requestBody.put("pageParam", pageParam);
        requestBody.put("instanceType", "");
        requestBody.put("storageType", null);
        requestBody.put("withMetrics", true);
        requestBody.put("withBehaviors", true);
        requestBody.put("withStatisticsInfo", true);

        HttpHeaders headers = buildHeaders(userSession, scopeType, spaceId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("获取中台库列表: {} (appId={}, scopeType={}, spaceId={}, user={})",
                url, effectiveAppId, headers.get("scopeType"), headers.get("spaceId"),
                userSession.getUsername());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            String respBody = response.getBody();
            log.info("中台库列表返回长度: {}", respBody != null ? respBody.length() : 0);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("获取中台库列表失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("获取中台库列表失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("获取中台库列表异常", e);
            throw new RuntimeException("获取中台库列表失败: " + e.getMessage(), e);
        }
    }

    // ==================== 数据源实例详情 ====================

    /**
     * 获取中台数据源实例详情（含连接信息）
     * POST daasECS/unit/instance/list
     *
     * @param datasourceId 数据源实例ID
     * @param userSession  用户 Session
     * @return 实例详情 JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchUnitInstanceDetail(String datasourceId, UserSession userSession) {
        String url = config.getUnitInstanceUrl();

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("instanceId", datasourceId);
        requestBody.put("withMetrics", true);
        requestBody.put("withBehaviors", false);

        HttpHeaders headers = buildHeaders(userSession);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("获取中台数据源实例详情: {} (datasourceId={})", url, datasourceId);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            String respBody = response.getBody();
            log.info("中台实例详情返回长度: {}", respBody != null ? respBody.length() : 0);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("获取中台实例详情失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("获取中台数据源实例详情失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("获取中台实例详情异常", e);
            throw new RuntimeException("获取中台数据源实例详情失败: " + e.getMessage(), e);
        }
    }

    // ==================== API 资源列表（独立接口） ====================

    /**
     * 获取中台 API 资源列表
     * POST daasMeta/apiResource/list
     *
     * @param scopeType   User / Space
     * @param spaceId     空间ID
     * @param userSession 用户 Session
     * @return API 资源列表 JSON
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchApiResourceList(String scopeType, String spaceId,
                                                    UserSession userSession) {
        String url = config.getApiResourceListUrl();

        Map<String, Object> extMap = new LinkedHashMap<>();
        extMap.put("listPageType", "ownerApiList");

        Map<String, Object> pageParam = new LinkedHashMap<>();
        pageParam.put("pageIndex", 1);
        pageParam.put("limit", 200);
        pageParam.put("sortField", "lastUpdateTime");
        pageParam.put("sortType", "desc");

        Map<String, Object> requestBody = new LinkedHashMap<>();
        String effectiveSpaceId = (spaceId != null && !spaceId.isEmpty())
                ? spaceId : config.getApiSpaceId();
        if (effectiveSpaceId != null && !effectiveSpaceId.isEmpty()) {
            requestBody.put("daas_space_id", effectiveSpaceId);
        }
        requestBody.put("pageParam", pageParam);
        requestBody.put("listType", "owner");
        requestBody.put("extMap", extMap);

        HttpHeaders headers = buildHeaders(userSession, scopeType, spaceId);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("获取中台 API 资源列表: {} (scopeType={}, spaceId={}, user={})",
                url, headers.get("scopeType"), effectiveSpaceId, userSession.getUsername());

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);
            String respBody = response.getBody();
            log.info("中台 API 资源列表返回长度: {}", respBody != null ? respBody.length() : 0);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("获取中台 API 资源列表失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("获取中台 API 资源列表失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("获取中台 API 资源列表异常", e);
            throw new RuntimeException("获取中台 API 资源列表失败: " + e.getMessage(), e);
        }
    }

    // ==================== 从中台构建 DB 连接 ====================

    /**
     * 从中台实例详情中解析出 DbConnection 对象
     *
     * 中台 metricList 中包含：
     *   - dbname: 数据库名
     * 其他连接信息从 classCodeInfo 和 accountId 中推导：
     *   - classCodeInfo.code: 数据库类型（doris → MySQL, dm → Dameng）
     *   - classCodeInfo.hasAccount: 是否有账号信息
     *
     * 注意：中台返回的实例详情中可能不直接返回 host/port/username/password，
     * 这些需要通过 accountId 调用账号管理接口获取。
     * 当前 MVP 版本：从 metricList 解析可用的连接信息，
     * 如果缺少则使用默认值（localhost + 中台达梦库配置）。
     */
    public DbConnection buildConnectionFromZhongtai(Map<String, Object> instanceDetail) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataList = extractDataList(instanceDetail);
        if (dataList.isEmpty()) {
            throw new RuntimeException("未找到数据源实例信息");
        }

        Map<String, Object> instance = dataList.get(0);

        // 解析数据库类型
        @SuppressWarnings("unchecked")
        Map<String, Object> classCodeInfo = (Map<String, Object>) instance.get("classCodeInfo");
        String engineCode = classCodeInfo != null ? (String) classCodeInfo.get("code") : "doris";
        String dbType = mapEngineToDbType(engineCode);

        // 从 metricList 解析连接参数
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metricList = (List<Map<String, Object>>) instance.get("metricList");
        Map<String, String> metrics = new LinkedHashMap<>();
        if (metricList != null) {
            for (Map<String, Object> m : metricList) {
                String key = (String) m.get("metricKey");
                String value = (String) m.get("metricValue");
                if (key != null && value != null) {
                    metrics.put(key, value);
                }
            }
        }

        String dbname = metrics.getOrDefault("dbname", instance.get("instanceCode") != null
                ? instance.get("instanceCode").toString() : "default");
        String displayName = instance.get("instanceDisplayName") != null
                ? instance.get("instanceDisplayName").toString() : dbname;

        // 构建 DbConnection
        DbConnection conn = new DbConnection();
        conn.setConnId("zhongtai_" + instance.get("instanceId"));
        conn.setConnName("中台-" + displayName);
        conn.setDbType(dbType);
        conn.setHost(metrics.getOrDefault("host", "")); // 遗留路径：真实 host 应来自 connect/info，不再兜底到硬编码 IP
        String portStr = metrics.getOrDefault("port",
                "Dameng".equalsIgnoreCase(dbType) ? "5236" : "9030");
        conn.setPort(Integer.parseInt(portStr));
        conn.setUsername(metrics.getOrDefault("username", "SYSDBA"));
        conn.setPassword(metrics.getOrDefault("password", ""));
        conn.setSchemaName(dbname);

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        conn.setCreateTime(now);
        conn.setUpdateTime(now);

        log.info("从中台构建连接: {} ({}:{}/{}, type={})",
                conn.getConnName(), conn.getHost(), conn.getPort(), dbname, dbType);

        return conn;
    }

    /**
     * 引擎类型码 → DbConnection.dbType 映射
     */
    private String mapEngineToDbType(String engineCode) {
        if (engineCode == null) return "MySQL";
        switch (engineCode.toLowerCase()) {
            case "dm":
            case "dameng":
                return "Dameng";
            case "doris":
            case "mysql":
                return "MySQL";
            default:
                // 大多数中台数据源使用 Doris（MySQL协议）
                return "MySQL";
        }
    }

    /**
     * 从多层嵌套的中台响应中提取 data 列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractDataList(Map<String, Object> response) {
        // 尝试 details.data → data
        Object details = response.get("details");
        if (details instanceof Map) {
            Object data = ((Map<String, Object>) details).get("data");
            if (data instanceof List) {
                return (List<Map<String, Object>>) data;
            }
        }
        Object data = response.get("data");
        if (data instanceof List) {
            return (List<Map<String, Object>>) data;
        }
        return Collections.emptyList();
    }

    // ==================== 连接信息（connect/info） ====================

    /**
     * 获取数据源连接信息（host/port/账号密码）
     * GET daasECS/plugin/connect/info?instanceId=xxx
     * 彬彬总提供：Doris 多库，通过 datasourceId(instanceId) 查库的连接信息
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fetchConnectInfo(String datasourceId, UserSession userSession) {
        String url = config.getConnectInfoUrl() + "?instanceId=" + datasourceId;
        HttpHeaders headers = buildHeaders(userSession);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        log.info("获取中台连接信息: {} (datasourceId={})", url, datasourceId);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            String respBody = response.getBody();
            log.info("中台连接信息原始返回: {}", respBody);
            return objectMapper.readValue(respBody, Map.class);
        } catch (HttpClientErrorException e) {
            log.error("获取中台连接信息失败: HTTP {} {}", e.getStatusCode(), e.getMessage());
            throw new RuntimeException("获取中台连接信息失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("获取中台连接信息异常", e);
            throw new RuntimeException("获取中台连接信息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 connect/info 返回中解析出 DbConnection
     * 兼容多种返回结构：details.data（Map 或 List[0]）、顶层 data、或字段直接在顶层
     */
    @SuppressWarnings("unchecked")
    public DbConnection buildConnectionFromConnectInfo(Map<String, Object> connectResponse) {
        Map<String, Object> info = extractConnectInfo(connectResponse);
        if (info == null || info.isEmpty()) {
            throw new RuntimeException("连接信息为空（connect/info 未返回有效数据）");
        }

        Map<String, Object> connectParams = getMap(info, "connectParams");
        Map<String, Object> account = getMap(info, "account");

        String engine = getString(info, "type", "dbType", "engine", "engineCode", "appClassCode", "typeCode");
        String dbType = mapEngineToDbType(engine);

        // host：顶层 connectIps/host/ip，或 connectParams.ip/dataIp
        String host = getString(info, "connectIps", "connectIp", "host", "ip", "hostIp", "hostName", "hostname");
        if (host == null && connectParams != null) {
            host = getString(connectParams, "ip", "host", "hostIp", "dataIp");
        }

        // port：顶层 port，或 connectParams.port
        String port = getString(info, "port");
        if (port == null && connectParams != null) {
            port = getString(connectParams, "port");
        }

        // username：account.loginId，或 account.extQuota 里 metricKey=username 的 metricValue，或顶层
        String username = null;
        if (account != null) {
            username = getString(account, "loginId", "loginName", "userName", "username", "account", "user");
            if (username == null) username = getMetricValue(account, "username");
        }
        if (username == null) username = getString(info, "username", "userName", "account", "user", "loginId");

        // password：account.password，或 account.extQuota 里 metricKey=password 的 metricValue，或顶层
        String password = null;
        if (account != null) {
            password = getString(account, "password", "pwd", "passwd", "passWord");
            if (password == null) password = getMetricValue(account, "password");
        }
        if (password == null) password = getString(info, "password", "pwd", "passwd");

        // dbname：catalog，或 connectParams.dbname，或顶层
        String dbname = getString(info, "catalog", "dbname", "dbName", "database", "schema", "schemaName");
        if (dbname == null && connectParams != null) {
            dbname = getString(connectParams, "dbname", "dbName", "database", "schema");
        }

        if (host == null || host.isEmpty()) {
            throw new RuntimeException("连接信息缺少 host，原始返回: " + info);
        }

        DbConnection conn = new DbConnection();
        conn.setConnId("zhongtai_" + (getString(info, "instanceId", "datasourceId") != null
                ? getString(info, "instanceId", "datasourceId") : "conn"));
        conn.setConnName("中台-" + (dbname != null ? dbname : "conn"));
        conn.setDbType(dbType);
        conn.setHost(host);
        int portNum;
        if (port != null && !port.isEmpty()) {
            try {
                portNum = Integer.parseInt(port.trim());
            } catch (NumberFormatException e) {
                portNum = "Dameng".equalsIgnoreCase(dbType) ? 5236 : 9030;
            }
        } else {
            portNum = "Dameng".equalsIgnoreCase(dbType) ? 5236 : 9030;
        }
        conn.setPort(portNum);
        conn.setUsername(username != null ? username : "");
        conn.setPassword(password != null ? password : "");
        conn.setSchemaName(dbname);

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        conn.setCreateTime(now);
        conn.setUpdateTime(now);

        log.info("从 connect/info 构建连接: {} ({}:{}/{}, type={}, user={})",
                conn.getConnName(), conn.getHost(), conn.getPort(), dbname, dbType, username);
        return conn;
    }

    /** 从 connect/info 多层返回里提取连接信息对象 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractConnectInfo(Map<String, Object> response) {
        if (response == null) return null;
        Object details = response.get("details");
        if (details instanceof Map) {
            Map<String, Object> detailsMap = (Map<String, Object>) details;
            Object data = detailsMap.get("data");
            if (data instanceof Map) return (Map<String, Object>) data;
            if (data instanceof List && !((List<?>) data).isEmpty() && ((List<?>) data).get(0) instanceof Map) {
                return (Map<String, Object>) ((List<?>) data).get(0);
            }
            // connect/info 里 details 本身就是连接信息对象（无 data 包裹）
            return detailsMap;
        }
        Object data = response.get("data");
        if (data instanceof Map) return (Map<String, Object>) data;
        if (data instanceof List && !((List<?>) data).isEmpty() && ((List<?>) data).get(0) instanceof Map) {
            return (Map<String, Object>) ((List<?>) data).get(0);
        }
        // 字段直接在顶层
        return response;
    }

    /** 取嵌套子对象 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getMap(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object v = map.get(key);
        if (v instanceof Map) return (Map<String, Object>) v;
        return null;
    }

    /** 从 account.extQuota 里按 metricKey 取 metricValue */
    @SuppressWarnings("unchecked")
    private String getMetricValue(Map<String, Object> account, String metricKey) {
        Object extQuota = account.get("extQuota");
        if (extQuota instanceof List) {
            for (Object item : (List<?>) extQuota) {
                if (item instanceof Map) {
                    Map<String, Object> m = (Map<String, Object>) item;
                    if (metricKey.equals(m.get("metricKey"))) {
                        Object v = m.get("metricValue");
                        return v != null ? v.toString() : null;
                    }
                }
            }
        }
        return null;
    }

    /** 依次尝试多个 key，返回第一个非空字符串值 */
    private String getString(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object v = map.get(key);
            if (v != null && !v.toString().trim().isEmpty()) {
                return v.toString().trim();
            }
        }
        return null;
    }
}
