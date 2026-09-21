package com.antv.l7vp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 登录模式配置（application.properties 中 l7vp.auth.* 前缀）。
 *
 * mode:
 *   sso   = 中台 SSO 集成登录（默认），走 zhongtai.sso.* 的授权码流程
 *   local = 本地开放访问：无需登录直接进首页，后端以默认本地用户自动建立会话，不依赖中台 SSO
 *
 * 值写死在本文件、改后重新打包生效（与 flink.* 约定一致）。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "l7vp.auth")
public class L7vpAuthProperties {

    /** 登录模式：sso | local */
    private String mode = "sso";

    /** local 模式下默认本地会话的显示名（顶栏可读到） */
    private String localDisplayName = "本地用户";

    /** 是否本地开放访问模式 */
    public boolean isLocal() {
        return "local".equalsIgnoreCase(mode);
    }
}
