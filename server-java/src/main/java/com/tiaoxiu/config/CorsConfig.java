package com.tiaoxiu.config;

import com.tiaoxiu.interceptor.WriteGuardInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 跨域（CORS）配置。
 *
 * <p>前后端分离部署时，前端站点与后端接口不同源，浏览器会先发 OPTIONS 预检请求，
 * 必须在服务端显式放行来源、方法与请求头，否则前端所有接口都会被调用浏览器拦截。
 *
 * <p>允许的来源通过配置项 {@code app.cors-allowed-origins} 控制（逗号分隔，支持通配符模式），
 * 便于开发 / 测试 / 生产环境差异化配置，避免把来源硬编码进代码。
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    /** 允许跨域访问的来源模式列表，逗号分隔，默认 {@code *}（开发环境全放行） */
    @Value("${app.cors-allowed-origins:*}")
    private String allowedOrigins;

    /** 写操作串行化拦截器，由 Spring 注入 */
    private final WriteGuardInterceptor writeGuardInterceptor;

    public CorsConfig(@Value("${app.cors-allowed-origins:*}") String allowedOrigins,
                      WriteGuardInterceptor writeGuardInterceptor) {
        this.allowedOrigins = allowedOrigins;
        this.writeGuardInterceptor = writeGuardInterceptor;
    }

    /**
     * 注册全局 CORS 规则，对所有接口路径生效。
     *
     * <p>注意：开启 {@code allowCredentials(true)} 时不能使用 {@code allowedOrigins("*")}，
     * 因此这里改用 {@code allowedOriginPatterns}，它支持带通配的来源模式且与凭证兼容。
     * {@code maxAge(3600)} 让浏览器缓存预检结果 1 小时，减少多余的 OPTIONS 请求。
     *
     * @param registry Spring MVC 提供的 CORS 注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }

    /**
     * 注册写操作拦截器：对所有 /api/** 下的写请求（POST/PUT/DELETE）施加全局串行 + 冷却约束。
     * 登录接口在拦截器内部被排除。
     *
     * @param registry Spring MVC 拦截器注册器
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(writeGuardInterceptor).addPathPatterns("/api/**");
    }
}
