package com.antv.l7vp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 数据中台 SSO + API 配置
 */
@Configuration
public class ZhongtaiConfig {

    // ============ SSO OAuth2 端点 ============

    @Value("${zhongtai.sso.authorize-url}")
    private String ssoAuthorizeUrl;

    @Value("${zhongtai.sso.token-url}")
    private String ssoTokenUrl;

    @Value("${zhongtai.sso.userinfo-url}")
    private String ssoUserinfoUrl;

    @Value("${zhongtai.sso.logout-url}")
    private String ssoLogoutUrl;

    @Value("${zhongtai.sso.redirect-uri}")
    private String redirectUri;

    @Value("${zhongtai.sso.frontend-url}")
    private String frontendUrl;

    @Value("${zhongtai.sso.client-id}")
    private String ssoClientId;

    @Value("${zhongtai.sso.client-secret}")
    private String ssoClientSecret;

    // ============ 中台 API 地址 ============

    @Value("${zhongtai.api.resource-list-url}")
    private String resourceListUrl;

    @Value("${zhongtai.api.unit-instance-url}")
    private String unitInstanceUrl;

    @Value("${zhongtai.api.connect-info-url}")
    private String connectInfoUrl;

    @Value("${zhongtai.api.api-resource-list-url}")
    private String apiResourceListUrl;

    @Value("${zhongtai.api.app-id:}")
    private String appId;

    // ============ 中台 API 调用默认参数 ============

    @Value("${zhongtai.api.space-id:default}")
    private String apiSpaceId;

    @Value("${zhongtai.api.scope-type:Space}")
    private String apiScopeType;

    @Value("${zhongtai.api.tenant-id:}")
    private String apiTenantId;

    // ============ Session 配置 ============

    @Value("${zhongtai.session.max-inactive-interval:28800}")
    private int sessionMaxInactiveInterval;

    // ============ Getters ============

    public String getSsoAuthorizeUrl() { return ssoAuthorizeUrl; }
    public String getSsoTokenUrl() { return ssoTokenUrl; }
    public String getSsoUserinfoUrl() { return ssoUserinfoUrl; }
    public String getSsoLogoutUrl() { return ssoLogoutUrl; }
    public String getRedirectUri() { return redirectUri; }
    public String getFrontendUrl() { return frontendUrl; }
    public String getSsoClientId() { return ssoClientId; }
    public String getSsoClientSecret() { return ssoClientSecret; }
    public String getApiSpaceId() { return apiSpaceId; }
    public String getApiScopeType() { return apiScopeType; }
    public String getApiTenantId() { return apiTenantId; }
    public String getResourceListUrl() { return resourceListUrl; }
    public String getUnitInstanceUrl() { return unitInstanceUrl; }
    public String getConnectInfoUrl() { return connectInfoUrl; }
    public String getApiResourceListUrl() { return apiResourceListUrl; }
    public String getAppId() { return appId; }
    public int getSessionMaxInactiveInterval() { return sessionMaxInactiveInterval; }

    // ============ 衍生方法 ============

    /** Basic Auth Header 值: "Basic base64(clientId:clientSecret)" */
    public String getBasicAuthHeader() {
        String credentials = ssoClientId + ":" + ssoClientSecret;
        return "Basic " + java.util.Base64.getEncoder().encodeToString(credentials.getBytes());
    }
}
