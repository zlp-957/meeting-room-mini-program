package com.meeting.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Web 配置：Sa-Token 鉴权 + CORS
 *
 * <p>P0-1 / P1-4：角色由 {@link StpInterfaceImpl} 提供；管理员与用户使用不同 loginId 前缀，
 * 因此管理员 token 无法调用用户端接口，普通用户也无法访问后台接口。</p>
 */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 完全公开的路径 */
    private static final String[] PUBLIC_PATHS = {
            "/api/auth/**",
            "/api/room/list",
            "/api/room/*",
            "/api/announcement/**",
            "/api/admin/auth/login",
            "/api/admin/auth/captcha"
    };

    /** 头像等上传文件的落盘目录，与 FileStorageService 共用同一配置项 */
    @Value("${meeting.upload.dir:upload}")
    private String uploadDir;

    /** 允许跨域的来源白名单（逗号分隔），不再使用通配符 */
    @Value("${meeting.cors.allowed-origins:}")
    private String allowedOriginsRaw;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> {
            // 管理端接口：必须已登录且为 admin 角色
            SaRouter.match("/api/admin/**")
                    .notMatch("/api/admin/auth/login", "/api/admin/auth/captcha")
                    .check(r -> {
                        StpUtil.checkLogin();
                        StpUtil.checkRole("admin");
                    });

            // 用户端接口：必须已登录且为 user 身份
            // 注意：必须显式排除 /api/admin/**，否则后台请求会在这里被当作普通用户再校验一次而返回 403
            SaRouter.match("/api/**")
                    .notMatch("/api/admin/**")
                    .notMatch(PUBLIC_PATHS)
                    .check(r -> {
                        StpUtil.checkLogin();
                        StpUtil.checkRole("user");
                    });
        })).addPathPatterns("/**");
    }

    /**
     * 把上传目录映射成静态资源，供小程序 image 组件直接访问。
     *
     * <p>路径不在 /api/** 下，因此不会被上面的 Sa-Token 拦截器要求登录
     * （图片请求带不上 Authorization 头），但文件名是 UUID，无法枚举。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(uploadDir).toAbsolutePath().normalize().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/upload/**").addResourceLocations(location);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins();
        if (origins.length == 0) {
            // 没有配置白名单就不开放跨域：宁可管理端先调不通（可立刻发现并配置），
            // 也不要默认放开后被任意站点以已登录用户的身份调接口。
            log.warn("[CORS] meeting.cors.allowed-origins 未配置，跨域请求将全部被拒绝。"
                    + "开发环境默认值见 application.yml，生产环境请设置为实际管理端域名。");
            return;
        }
        registry.addMapping("/**")
                // 用 allowedOrigins 精确匹配，不再用 "*"：
                // 通配 + allowCredentials(true) 等于允许任意站点携带用户凭证调用本服务
                .allowedOrigins(origins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
        log.info("[CORS] 允许的跨域来源：{}", String.join(", ", origins));
    }

    /**
     * 解析白名单配置（逗号分隔）。空白项会被丢弃，避免 "a,,b" 这类配置导致启动异常。
     */
    private String[] allowedOrigins() {
        if (allowedOriginsRaw == null || allowedOriginsRaw.trim().isEmpty()) {
            return new String[0];
        }
        return Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
