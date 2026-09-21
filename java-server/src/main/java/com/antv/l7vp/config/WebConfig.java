package com.antv.l7vp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${l7vp.thumbnails.path:/opt/l7vp/frontend/thumbnails}")
    private String thumbnailsPath;

    @Value("${l7vp.thumbnails.url-prefix:/thumbnails}")
    private String thumbnailsUrlPrefix;

    @Value("${l7vp.icons.path:/opt/l7vp/frontend/icons}")
    private String iconsPath;

    @Value("${l7vp.icons.url-prefix:/icons}")
    private String iconsUrlPrefix;

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return mapper;
    }

    @Bean
    public RestTemplate restTemplate() {
        // JDK HttpURLConnection 不支持 PATCH（ProtocolException），而 Flink 作业取消走 PATCH /jobs/{jid}?mode=cancel，
        // 故用 Apache HttpClient（HttpComponentsClientHttpRequestFactory）作为底层连接器。
        return new RestTemplate(new HttpComponentsClientHttpRequestFactory());
    }

    /**
     * 本地开放访问过滤器：l7vp.auth.mode=local 时自动建立默认本地会话（免登录进首页）。
     * sso 模式(默认)下 LocalUserFilter 直接放行，零开销。
     */
    @Bean
    public FilterRegistrationBean<LocalUserFilter> localUserFilter(L7vpAuthProperties authProperties) {
        LocalUserFilter filter = new LocalUserFilter(authProperties);
        FilterRegistrationBean<LocalUserFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/api/*", "/ws/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(thumbnailsUrlPrefix + "/**")
                .addResourceLocations("file:" + new File(thumbnailsPath).getAbsolutePath() + File.separator);

        // 图标静态资源（png/svg 等），支持子目录分类
        registry.addResourceHandler(iconsUrlPrefix + "/**")
                .addResourceLocations("file:" + new File(iconsPath).getAbsolutePath() + File.separator);

        // 前端 SPA 静态资源（单 jar：前端 dist 内嵌于 classpath:/static/）
        // 经中台 nginx 剥掉 /l7vp 前缀后走默认 "/" 静态；此处支持直连 3001 以 /l7vp/ 访问
        registry.addResourceHandler("/l7vp/**")
                .addResourceLocations("classpath:/static/");
    }
}