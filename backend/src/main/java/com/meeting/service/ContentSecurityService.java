package com.meeting.service;

import cn.hutool.core.util.StrUtil;
import com.meeting.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 用户自填内容的安全检测
 *
 * <p>昵称这类用户自填内容，光靠本地词库挡不住所有风险（尤其是政治敏感、隐晦辱骂），
 * 因此再叠一层微信官方 {@code msg_sec_check}。两层各司其职：</p>
 * <ul>
 *   <li><b>本地词库</b>（{@link NicknameCheckService}）：无网络依赖，能拦常见辱骂、冒充官方、广告引流，
 *       响应快，接口故障时仍有效</li>
 *   <li><b>微信官方检测</b>（本类）：覆盖政治敏感、色情、违禁等分类，作为兜底</li>
 * </ul>
 *
 * <p>降级策略：微信接口不可用（未配置真实参数、网络异常、额度耗尽）时<b>放行</b>并记日志，
 * 而不是阻塞用户改昵称——本地词库仍在生效，不会出现「改不了昵称」的死锁。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentSecurityService {

    private final WechatService wechatService;

    /** 总开关：关掉后只走本地词库 */
    @Value("${meeting.content-security.enabled:true}")
    private boolean enabled;

    /**
     * 校验文本是否安全，不安全直接抛 {@link BusinessException}。
     *
     * @param content 待校验文本
     * @param openid  用户 openid；短信注册用户可能为空，此时跳过官方检测（接口要求 openid）
     */
    public void assertTextSafe(String content, String openid) {
        if (!enabled) {
            return;
        }
        if (StrUtil.isBlank(openid)) {
            // msg_sec_check v2 必须传 openid，没有就交给本地词库
            return;
        }

        boolean pass;
        try {
            pass = wechatService.checkTextSecurity(content, openid);
        } catch (Exception e) {
            log.warn("[内容安全] 微信检测调用失败，降级放行：{}", e.getMessage());
            return;
        }

        if (!pass) {
            throw new BusinessException(400, "昵称包含不当内容，请修改后重试");
        }
    }
}
