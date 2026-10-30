package com.meeting.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Arrays;

/**
 * 启动期安全断言。
 *
 * <p>以下配置一旦在生产环境生效，攻击者不需要任何密码就能接管任意账号，
 * 而这类问题在开发环境完全看不出来（开发环境本来就该用 mock）。
 * 所以不靠「记得改配置」，而是让服务直接起不来：</p>
 *
 * <ul>
 *   <li>{@code meeting.wechat-mock=true}：微信登录返回伪造 openid，任何人可登录任意账号；</li>
 *   <li>{@code meeting.captcha-debug=true}：图形验证码明文返回，等于没有验证码；</li>
 *   <li>微信 appid/secret 仍是占位符；</li>
 *   <li>数据库密码为空（多数 MySQL 配置下会连不上，少数配置下等于 root 空密码）。</li>
 * </ul>
 *
 * <p>非生产环境只打印警告，不阻断启动，避免影响本地联调。</p>
 */
@Slf4j
@Component
public class SafetyGuard {

    private static final String PROD = "prod";

    private final Environment environment;

    @Value("${meeting.wechat-mock:false}")
    private boolean wechatMock;

    @Value("${meeting.captcha-debug:false}")
    private boolean captchaDebug;

    @Value("${wechat.appid:}")
    private String wxAppid;

    @Value("${wechat.subscribe-templates.remind:}")
    private String tplRemind;

    @Value("${wechat.subscribe-templates.violation:}")
    private String tplViolation;

    @Value("${wechat.subscribe-templates.ban:}")
    private String tplBan;

    @Value("${spring.datasource.password:}")
    private String dbPassword;

    public SafetyGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void check() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains(PROD);

        if (!prod) {
            if (wechatMock || captchaDebug) {
                log.warn("[安全提示] 当前 profile={} 处于 mock 模式（wechatMock={} captchaDebug={}）。"
                                + "仅限本地开发使用，切勿用于任何对外可访问的环境。",
                        Arrays.toString(environment.getActiveProfiles()), wechatMock, captchaDebug);
            }
            return;
        }

        StringBuilder fatal = new StringBuilder();
        if (wechatMock) {
            fatal.append("\n  - meeting.wechat-mock=true：微信登录会返回伪造 openid，任何人可登录任意账号");
        }
        if (captchaDebug) {
            fatal.append("\n  - meeting.captcha-debug=true：图形验证码明文返回，等于没有验证码");
        }
        if (wxAppid == null || wxAppid.trim().isEmpty() || wxAppid.startsWith("your_")) {
            fatal.append("\n  - wechat.appid 仍是占位符，请通过环境变量 WECHAT_APPID 注入真实值");
        }
        if (dbPassword == null || dbPassword.trim().isEmpty()) {
            fatal.append("\n  - spring.datasource.password 为空，请通过环境变量 DB_PASSWORD 注入");
        }
        if (fatal.length() > 0) {
            throw new IllegalStateException("[启动中止] prod profile 存在不安全配置：" + fatal
                    + "\n请在 application-prod.yml / 环境变量中修正后重新启动。");
        }

        // 订阅消息是增强通道，缺失不阻断启动，但必须让人看见「提醒不会真的推送」
        if (isBlank(tplRemind) || isBlank(tplViolation) || isBlank(tplBan)) {
            log.warn("[订阅消息] 模板 ID 未配置完整（remind={} violation={} ban={}），"
                            + "临近提醒/违约/封禁将只落站内通知，不会推送到微信。",
                    isBlank(tplRemind) ? "空" : "已配置",
                    isBlank(tplViolation) ? "空" : "已配置",
                    isBlank(tplBan) ? "空" : "已配置");
        }
        log.info("[安全断言] prod profile 配置检查通过");
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
