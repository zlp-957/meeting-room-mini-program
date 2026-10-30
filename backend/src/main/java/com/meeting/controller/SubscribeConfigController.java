package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.service.SubscribeMessageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 订阅消息配置下发（用户端）。
 *
 * <p>为什么需要这个接口：订阅消息的授权只能在小程序里由用户点击完成
 * （{@code wx.requestSubscribeMessage}），而后端才知道模板 ID 是否配置。
 * 不做这一步，即使把模板 ID 填好，用户从未授权，后端下发时微信也会返回
 * 43101（用户拒绝/未订阅），表现为「配置都填了但就是收不到提醒」。</p>
 *
 * <p>返回值只包含模板 ID 与缺失场景，不含任何密钥信息，因此对登录用户开放即可。</p>
 */
@RestController
@RequestMapping("/api/subscribe")
@RequiredArgsConstructor
public class SubscribeConfigController {

    private final SubscribeMessageService subscribeMessageService;

    @GetMapping("/templates")
    public Result<Map<String, Object>> templates() {
        Map<String, Object> data = new HashMap<>();
        // 前端直接把这个数组交给 wx.requestSubscribeMessage
        data.put("tmplIds", subscribeMessageService.configuredTemplateIds());
        // 未配置的场景：前端据此决定「静默跳过」还是「提示管理员未开通」
        data.put("missingScenes", subscribeMessageService.missingScenes());
        return Result.success(data);
    }
}
