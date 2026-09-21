package com.antv.l7vp.config;

import com.antv.l7vp.controller.AuthController;
import com.antv.l7vp.dto.UserSession;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;

/**
 * 本地开放访问过滤器（仅 l7vp.auth.mode=local 时生效，sso 模式直接放行、零开销）。
 *
 * local 模式下自动以「默认本地用户」建立 HttpSession 会话，使 /api/auth/status 恒返回
 * authenticated=true，前端登录墙(useUser 跳 SSO)不再触发，实现「打开即进首页、免登录」。
 *
 * 注册范围 /api/* 与 /ws/*（见 WebConfig#localUserFilter）。默认本地会话无 accessToken，
 * 因此中台数据接口(/api/zhongtai/*)会因缺 token 返回 401，本地业务接口不受影响。
 */
public class LocalUserFilter extends OncePerRequestFilter {

    private final L7vpAuthProperties properties;

    public LocalUserFilter(L7vpAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // sso 模式不干预登录，完全维持现状
        if (!properties.isLocal()) {
            filterChain.doFilter(request, response);
            return;
        }
        // 预检请求不建会话
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        HttpSession session = request.getSession(false);
        UserSession existing = session == null ? null : AuthController.getSession(request);
        if (existing != null && !existing.isExpired()) {
            filterChain.doFilter(request, response);
            return;
        }

        // 无有效会话 → 以默认本地用户建立
        HttpSession s = request.getSession(true);
        s.setMaxInactiveInterval(28800); // 与 AuthController 中 SSO 会话一致：8 小时
        UserSession local = new UserSession(
                "local",                 // userId
                "local",                 // username
                properties.getLocalDisplayName(), // displayName
                "",                      // orgId（无中台）
                null,                    // accessToken（无 SSO → 中台接口不可用）
                null,
                null);                   // expiresAt=null → isExpired() 恒 false
        s.setAttribute(AuthController.SESSION_KEY, local);
        filterChain.doFilter(request, response);
    }
}
