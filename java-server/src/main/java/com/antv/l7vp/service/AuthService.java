package com.antv.l7vp.service;

import com.antv.l7vp.config.ZhongtaiConfig;
import com.antv.l7vp.dto.UserSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * SSO OAuth2 认证服务
 * - 拼接 SSO 登录 URL
 * - 用 authorization code 换取 access token
 * - 获取用户信息
 * - 刷新 token
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ZhongtaiConfig config;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 生成 SSO 登录跳转 URL (Authorization Code 模式)
     */
    public String buildAuthorizeUrl() {
        return config.getSsoAuthorizeUrl()
                + "?response_type=code"
                + "&client_id=" + config.getSsoClientId()
                + "&redirect_uri=" + config.getRedirectUri();
    }

    /**
     * 直接登录：用用户名密码走 password grant 换 token（备用方案）
     * POST /sso/oauth/2/token
     * Headers: Authorization: Basic base64(clientId:clientSecret)
     * Query: grant_type=password&username=xxx&password=xxx
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> loginWithPassword(String username, String password) {
        String url = config.getSsoTokenUrl()
                + "?grant_type=password"
                + "&username=" + username
                + "&password=" + password;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", config.getBasicAuthHeader());

        log.info("直接登录: POST {} (user={})", config.getSsoTokenUrl(), username);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            log.info("Token 响应: {}", body);
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("直接登录失败", e);
            throw new RuntimeException("登录失败: " + e.getMessage(), e);
        }
    }

    /**
     * 用 authorization code 换取 access_token + refresh_token
     * POST /sso/oauth/2/token
     * Headers: Authorization: Basic base64(clientId:clientSecret)
     * Body: grant_type=authorization_code&code=xxx
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> exchangeCodeForToken(String code) {
        String url = config.getSsoTokenUrl()
                + "?grant_type=authorization_code"
                + "&code=" + code;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", config.getBasicAuthHeader());

        log.info("交换授权码: POST {}", config.getSsoTokenUrl());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            log.info("Token 响应: {}", body);
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("交换授权码失败", e);
            throw new RuntimeException("SSO token 交换失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取用户信息
     * GET /sso/oauth2/userinfo
     * Headers: Authorization: Bearer {accessToken}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getUserInfo(String accessToken) {
        String url = config.getSsoUserinfoUrl();

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + accessToken);

        log.info("获取用户信息: GET {}", url);

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            log.info("UserInfo 响应: {}", body);
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("获取用户信息失败", e);
            throw new RuntimeException("获取用户信息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 刷新 access_token
     * POST /sso/oauth/2/token
     * Headers: Authorization: Basic base64(clientId:clientSecret)
     * Body: grant_type=refresh_token&refresh_token=xxx
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> refreshToken(String refreshToken) {
        String url = config.getSsoTokenUrl()
                + "?grant_type=refresh_token"
                + "&refresh_token=" + refreshToken;

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", config.getBasicAuthHeader());

        log.info("刷新 token: POST {}", config.getSsoTokenUrl());

        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            log.info("Refresh 响应: {}", body);
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            log.error("刷新 token 失败", e);
            throw new RuntimeException("Token 刷新失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 OAuth2 token 响应中构建 UserSession
     *
     * token 响应格式（已验证）:
     * {
     *   "access_token": "...", "refresh_token": "...", "expires_in": 36000,
     *   "username": "bingocc",
     *   "userinfo_sub": "e1c0f1fa...",
     *   "userinfo_login_name": "测试用户"
     * }
     *
     * userinfo 响应格式:
     * {
     *   "sub": "e1c0f1fa...", "username": "bingocc",
     *   "name": "bingocc", "login_name": "测试用户",
     *   "gender": "未设置"
     * }
     */
    public UserSession buildUserSession(Map<String, Object> tokenResponse) {
        String accessToken = getString(tokenResponse, "access_token");
        String refreshToken = getString(tokenResponse, "refresh_token");

        // 优先从 token 响应中提取用户信息（减少一次 API 调用）
        String userId = getString(tokenResponse, "userinfo_sub");
        String username = getString(tokenResponse, "username");
        String displayName = getString(tokenResponse, "userinfo_login_name");

        // 如果 token 响应中信息不全，补充调用 userinfo
        if (userId == null || username == null) {
            Map<String, Object> userInfo = getUserInfo(accessToken);
            if (userId == null) userId = getString(userInfo, "sub");
            if (username == null) username = getString(userInfo, "username");
            if (displayName == null) {
                displayName = getString(userInfo, "login_name");
                if (displayName == null) displayName = getString(userInfo, "name");
            }
        }

        // 计算过期时间
        long expiresIn = 36000L; // 默认 10 小时
        Object expObj = tokenResponse.get("expires_in");
        if (expObj instanceof Number) {
            expiresIn = ((Number) expObj).longValue();
        }

        LocalDateTime expiresAt = LocalDateTime.now().plusSeconds(expiresIn);

        // org_id 尝试从 userinfo 获取（token 响应中可能不包含）
        String orgId = getString(tokenResponse, "userinfo_org_id");
        if (orgId == null) {
            // 尝试二次调用获取 org_id
            try {
                Map<String, Object> userInfo = getUserInfo(accessToken);
                orgId = getString(userInfo, "org_id");
            } catch (Exception e) {
                log.warn("获取 org_id 失败，将使用空值", e);
            }
        }

        log.info("创建 UserSession: userId={}, username={}, displayName={}, orgId={}, expiresIn={}s",
                userId, username, displayName, orgId, expiresIn);

        return new UserSession(userId, username, displayName, orgId,
                accessToken, refreshToken, expiresAt);
    }

    /**
     * 构建 SSO 登出 URL
     */
    public String buildLogoutUrl() {
        return config.getSsoLogoutUrl()
                + "?post_logout_redirect_uri=" + config.getFrontendUrl();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
