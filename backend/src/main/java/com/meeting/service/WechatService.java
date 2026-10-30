package com.meeting.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.SecureUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.meeting.common.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 微信小程序登录服务
 *
 * <p>meeting.wechat-mock=true 时使用 mock 数据，便于本地联调；
 * 关闭 mock 后走微信官方接口：jscode2session 换 openid、getuserphonenumber 换手机号。</p>
 */
@Slf4j
@Service
public class WechatService {

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String PHONE_NUMBER_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    /** 内容安全检测（文本） */
    private static final String MSG_SEC_CHECK_URL = "https://api.weixin.qq.com/wxa/msg_sec_check";
    /** 微信接口超时（毫秒） */
    private static final int HTTP_TIMEOUT = 5000;
    /** access_token 提前 5 分钟过期，避免边界失效 */
    private static final long TOKEN_EXPIRE_AHEAD_MILLIS = 5 * 60 * 1000L;

    @Value("${wechat.appid}")
    private String appid;

    @Value("${wechat.secret}")
    private String secret;

    @Value("${meeting.wechat-mock}")
    private boolean mock;

    /** access_token 缓存（微信限制每日获取次数，必须缓存复用） */
    private String accessToken;
    private long accessTokenExpireAt;

    /**
     * 通过登录 code 获取 openid
     */
    public String getOpenid(String code) {
        if (mock) {
            // mock 模式：用 code 生成一个稳定的 openid（同一 code 得到同一 openid）
            String hash = SecureUtil.md5(code);
            return "mock_" + hash.substring(0, 24);
        }
        return callWechatCode2Session(code);
    }

    /**
     * 通过手机号授权 code 获取手机号
     */
    public String getPhoneNumber(String code) {
        if (mock) {
            // mock 模式：生成一个模拟手机号
            String hash = SecureUtil.md5(code);
            int suffix = Math.abs(hash.hashCode()) % 100000000;
            return "138" + String.format("%08d", suffix);
        }
        return callWechatPhoneNumber(code);
    }

    // ===== 真实微信接口 =====

    private String callWechatCode2Session(String code) {
        assertConfigured();
        String url = CODE2SESSION_URL + "?appid=" + appid + "&secret=" + secret
                + "&js_code=" + code + "&grant_type=authorization_code";
        JSONObject json = getJson(url, "微信登录失败");
        String openid = json.getStr("openid");
        if (StrUtil.isBlank(openid)) {
            throw new BusinessException("微信登录失败：未获取到 openid");
        }
        return openid;
    }

    private String callWechatPhoneNumber(String code) {
        assertConfigured();
        String body = JSONUtil.createObj().set("code", code).toString();
        JSONObject json = postJson(PHONE_NUMBER_URL + "?access_token=" + getAccessToken(), body, "获取手机号失败");

        // access_token 失效时刷新一次再重试
        Integer errcode = json.getInt("errcode");
        if (errcode != null && (errcode == 40001 || errcode == 42001 || errcode == 40014)) {
            refreshAccessToken();
            json = postJson(PHONE_NUMBER_URL + "?access_token=" + getAccessToken(), body, "获取手机号失败");
        }

        JSONObject info = json.getJSONObject("phone_info");
        String phone = info == null ? null : info.getStr("phoneNumber");
        if (StrUtil.isBlank(phone)) {
            throw new BusinessException("获取手机号失败：微信未返回手机号");
        }
        return phone;
    }

    /**
     * 获取 access_token（带缓存）
     */
    private synchronized String getAccessToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < accessTokenExpireAt) {
            return accessToken;
        }
        refreshAccessToken();
        return accessToken;
    }

    /**
     * 供订阅消息等服务复用 access_token（公开读取，不触发刷新）
     */
    public String getAccessTokenValue() {
        if (mock) {
            return null;
        }
        return getAccessToken();
    }

    /**
     * 微信内容安全检测（文本，msgSecCheck v2）
     *
     * <p>这是微信官方的内容审核能力，覆盖政治敏感、色情低俗、辱骂、违法违禁等分类，
     * 比自建词库可靠，适合放在昵称这类用户自填内容上做兜底。</p>
     *
     * @param content 待检测文本
     * @param openid  用户 openid（v2 接口要求，用于关联用户主体）
     * @return true=通过；false=判定风险
     * @throws BusinessException 接口调用失败（网络 / 鉴权问题），调用方应捕获后降级
     */
    public boolean checkTextSecurity(String content, String openid) {
        String token = getAccessTokenValue();
        if (StrUtil.isBlank(token)) {
            // mock 模式或未配置真实参数：跳过官方检测，由本地词库兜底
            log.debug("微信内容安全检测已跳过（未获取到 access_token）");
            return true;
        }

        JSONObject body = new JSONObject();
        body.put("content", content);
        body.put("version", 2);
        body.put("scene", 1); // 1=资料（昵称、签名等）
        body.put("openid", openid);

        JSONObject json = postJson(MSG_SEC_CHECK_URL + "?access_token=" + token,
                body.toString(), "微信内容安全检测失败");
        JSONObject result = json.getJSONObject("result");
        if (result == null) {
            return true;
        }
        String suggest = result.getStr("suggest");
        boolean risky = "risky".equalsIgnoreCase(suggest);
        if (risky) {
            log.info("[内容安全] 昵称被判定风险 label={}", result.getStr("label"));
        }
        return !risky;
    }

    private synchronized void refreshAccessToken() {
        String url = ACCESS_TOKEN_URL + "?grant_type=client_credential&appid=" + appid + "&secret=" + secret;
        JSONObject json = getJson(url, "获取微信 access_token 失败");
        String token = json.getStr("access_token");
        if (StrUtil.isBlank(token)) {
            throw new BusinessException("获取微信 access_token 失败");
        }
        Long expiresIn = json.getLong("expires_in", 7200L);
        accessToken = token;
        accessTokenExpireAt = System.currentTimeMillis() + expiresIn * 1000L - TOKEN_EXPIRE_AHEAD_MILLIS;
    }

    private JSONObject getJson(String url, String errorPrefix) {
        try (HttpResponse resp = HttpRequest.get(url).timeout(HTTP_TIMEOUT).execute()) {
            return parse(resp.body(), errorPrefix);
        }
    }

    private JSONObject postJson(String url, String body, String errorPrefix) {
        try (HttpResponse resp = HttpRequest.post(url)
                .header("Content-Type", "application/json")
                .body(body)
                .timeout(HTTP_TIMEOUT)
                .execute()) {
            return parse(resp.body(), errorPrefix);
        }
    }

    private JSONObject parse(String body, String errorPrefix) {
        if (StrUtil.isBlank(body) || !JSONUtil.isTypeJSONObject(body)) {
            log.warn("微信接口返回非 JSON：{}", body);
            throw new BusinessException(errorPrefix + "：接口返回异常");
        }
        JSONObject json = JSONUtil.parseObj(body);
        Integer errcode = json.getInt("errcode");
        // 注意：code2session 成功时也可能带 errcode=0
        if (errcode != null && errcode != 0) {
            String errmsg = json.getStr("errmsg");
            log.warn("微信接口错误 errcode={} errmsg={}", errcode, errmsg);
            throw new BusinessException(errorPrefix + "（" + errcode + "）：" + StrUtil.blankToDefault(errmsg, "未知错误"));
        }
        return json;
    }

    /**
     * 防止把占位配置带到生产：占位值直接给出明确提示，而不是等微信返回 40013
     */
    private void assertConfigured() {
        if (StrUtil.isBlank(appid) || StrUtil.isBlank(secret)
                || appid.startsWith("your_") || secret.startsWith("your_")) {
            throw new BusinessException("微信参数未配置：请在 application.yml 填写真实 wechat.appid / wechat.secret");
        }
    }
}
