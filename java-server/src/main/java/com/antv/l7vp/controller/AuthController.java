package com.antv.l7vp.controller;

import com.antv.l7vp.config.L7vpAuthProperties;
import com.antv.l7vp.config.ZhongtaiConfig;
import com.antv.l7vp.dto.UserSession;
import com.antv.l7vp.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SSO 认证 API (/api/auth/*)
 *
 * 流程:
 * 1. 前端 GET /api/auth/status → 未登录 → GET /api/auth/login → 获取 SSO URL → 跳转
 * 2. SSO 回调 GET /api/auth/callback?code=xxx → 后端换 token → 存 Session → 302 前端
 * 3. 前端 GET /api/auth/status → 已登录 → 渲染应用
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    public static final String SESSION_KEY = "userSession";

    @Autowired
    private AuthService authService;

    @Autowired
    private ZhongtaiConfig zhongtaiConfig;

    @Autowired
    private L7vpAuthProperties authProperties;

    /**
     * 获取 SSO 登录跳转 URL
     * 前端调用后，用返回的 redirectUrl 跳转到中台登录页
     */
    @GetMapping("/login")
    public ResponseEntity<Map<String, Object>> login() {
        // local（本地开放访问）模式无需 SSO，防御性返回错误（正常前端不调用）
        if (authProperties.isLocal()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "当前为本地模式，无需 SSO 登录");
            return ResponseEntity.badRequest().body(err);
        }
        String redirectUrl = authService.buildAuthorizeUrl();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("redirectUrl", redirectUrl);
        result.put("mode", "redirect");  // SSO 跳转模式
        log.info("SSO 登录 URL: {}", redirectUrl);
        return ResponseEntity.ok(result);
    }

    /**
     * 直接登录（备用方案）：前端提交用户名密码，后端走 password grant
     * POST /api/auth/login
     * Body: { username, password }
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> loginDirect(@RequestBody Map<String, String> body,
                                                            HttpServletRequest request) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "用户名和密码不能为空");
            return ResponseEntity.badRequest().body(err);
        }

        try {
            Map<String, Object> tokenResponse = authService.loginWithPassword(username, password);
            UserSession userSession = authService.buildUserSession(tokenResponse);

            HttpSession session = request.getSession(true);
            session.setMaxInactiveInterval(28800);
            session.setAttribute(SESSION_KEY, userSession);

            log.info("直接登录成功: {} ({})", userSession.getUsername(), userSession.getDisplayName());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("username", userSession.getUsername());
            result.put("displayName", userSession.getDisplayName());
            result.put("userId", userSession.getUserId());
            result.put("orgId", userSession.getOrgId());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("直接登录失败", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(401).body(err);
        }
    }

    /**
     * OAuth2 回调 — SSO 登录成功后重定向到此
     * GET /api/auth/callback?code=xxx
     * 后端用 code 换 token → 获取用户信息 → 存 Session → 302 到前端首页
     */
    @GetMapping("/callback")
    public void callback(@RequestParam("code") String code,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        log.info("OAuth2 回调收到 code: {}", code.substring(0, Math.min(20, code.length())) + "...");

        try {
            // 1. 用 code 换 token
            Map<String, Object> tokenResponse = authService.exchangeCodeForToken(code);

            // 2. 构建 UserSession（含用户信息 + org_id）
            UserSession userSession = authService.buildUserSession(tokenResponse);

            // 3. 存入 HttpSession
            HttpSession session = request.getSession(true);
            session.setMaxInactiveInterval(28800); // 8 小时
            session.setAttribute(SESSION_KEY, userSession);

            log.info("用户 {} ({}) 登录成功，session={}",
                    userSession.getUsername(), userSession.getDisplayName(), session.getId());

            // 4. 重定向到前端首页（前端地址来自 zhongtai.sso.frontend-url，部署时用 APP_FRONTEND_URL 覆盖）
            String frontendUrl = zhongtaiConfig.getFrontendUrl() + "?login_success=1"
                    + "&username=" + URLEncoder.encode(userSession.getUsername() != null ? userSession.getUsername() : "", StandardCharsets.UTF_8.name())
                    + "&name=" + URLEncoder.encode(userSession.getDisplayName() != null ? userSession.getDisplayName() : "", StandardCharsets.UTF_8.name());
            response.sendRedirect(frontendUrl);
        } catch (Exception e) {
            log.error("OAuth2 回调处理失败", e);
            // 重定向到前端并带上错误信息
            response.sendRedirect(zhongtaiConfig.getFrontendUrl() + "?login_error=1&message="
                    + URLEncoder.encode(e.getMessage(), StandardCharsets.UTF_8.name()));
        }
    }

    /**
     * 检查登录状态
     * 前端页面加载时调用
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        UserSession userSession = getSession(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("authMode", authProperties.getMode());  // sso | local，前端据此区分展示

        if (userSession != null && !userSession.isExpired()) {
            result.put("authenticated", true);
            result.put("username", userSession.getUsername());
            result.put("displayName", userSession.getDisplayName());
            result.put("userId", userSession.getUserId());
            result.put("orgId", userSession.getOrgId());
        } else {
            result.put("authenticated", false);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 获取当前用户详细信息（需登录）
     */
    @GetMapping("/userinfo")
    public ResponseEntity<Map<String, Object>> userinfo(HttpServletRequest request) {
        UserSession userSession = getSession(request);
        if (userSession == null || userSession.isExpired()) {
            return ResponseEntity.status(401).build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userSession.getUserId());
        result.put("username", userSession.getUsername());
        result.put("displayName", userSession.getDisplayName());
        result.put("orgId", userSession.getOrgId());
        return ResponseEntity.ok(result);
    }

    /**
     * 登出
     * 清除 Session → 返回 SSO 登出 URL
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
            log.info("用户已登出");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (authProperties.isLocal()) {
            // local 模式无 SSO 登出地址，返回空串；前端不跳转
            result.put("logoutUrl", "");
        } else {
            result.put("logoutUrl", authService.buildLogoutUrl());
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 刷新 token（前端检测到即将过期时主动调用）
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(HttpServletRequest request) {
        UserSession userSession = getSession(request);
        if (userSession == null || userSession.getRefreshToken() == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            Map<String, Object> tokenResponse = authService.refreshToken(userSession.getRefreshToken());
            String newAccessToken = getString(tokenResponse, "access_token");
            String newRefreshToken = getString(tokenResponse, "refresh_token");
            long expiresIn = 36000L;
            Object expObj = tokenResponse.get("expires_in");
            if (expObj instanceof Number) {
                expiresIn = ((Number) expObj).longValue();
            }

            userSession.setAccessToken(newAccessToken);
            if (newRefreshToken != null) {
                userSession.setRefreshToken(newRefreshToken);
            }
            userSession.setExpiresAt(java.time.LocalDateTime.now().plusSeconds(expiresIn));
            request.getSession().setAttribute(SESSION_KEY, userSession);

            log.info("Token 刷新成功，新过期时间: {}", userSession.getExpiresAt());
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("success", true);
            return ResponseEntity.ok(ok);
        } catch (Exception e) {
            log.error("刷新 token 失败", e);
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", e.getMessage());
            return ResponseEntity.status(500).body(err);
        }
    }

    // ============ 工具方法 ============

    /** 从 HttpSession 获取 UserSession */
    public static UserSession getSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        Object attr = session.getAttribute(SESSION_KEY);
        if (attr instanceof UserSession) {
            return (UserSession) attr;
        }
        return null;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
