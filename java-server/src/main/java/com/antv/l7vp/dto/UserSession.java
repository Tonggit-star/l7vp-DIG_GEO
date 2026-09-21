package com.antv.l7vp.dto;

import java.time.LocalDateTime;

/**
 * 存储在 HttpSession 中的用户认证信息
 */
public class UserSession {
    private String userId;        // userinfo.sub
    private String username;      // 登录账号
    private String displayName;   // login_name (显示名)
    private String orgId;         // 组织 ID
    private String accessToken;
    private String refreshToken;
    private LocalDateTime expiresAt;

    public UserSession() {}

    public UserSession(String userId, String username, String displayName, String orgId,
                       String accessToken, String refreshToken, LocalDateTime expiresAt) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.orgId = orgId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
    }

    /** Token 是否已过期（提前 60 秒视为过期） */
    public boolean isExpired() {
        return expiresAt != null && LocalDateTime.now().plusSeconds(60).isAfter(expiresAt);
    }

    // ============ Getters & Setters ============

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getOrgId() { return orgId; }
    public void setOrgId(String orgId) { this.orgId = orgId; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
