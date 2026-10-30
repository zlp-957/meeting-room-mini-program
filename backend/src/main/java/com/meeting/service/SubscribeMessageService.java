package com.meeting.service;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 微信订阅消息下发服务
 *
 * <p>订阅消息是「增强触达」手段，站内通知才是可靠主通道。本服务所有失败都只记日志，
 * 绝不向上抛异常，避免影响预约 / 违约等主流程。</p>
 *
 * <p>前置条件：需在小程序后台申请订阅消息模板拿到 templateId，并保证
 * 用户已通过 wx.requestSubscribeMessage 授权（一次授权 = 一次下发额度）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscribeMessageService {

    private static final String SUBSCRIBE_SEND_URL = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    /** 各场景的订阅消息模板 ID（需替换为小程序后台实际申请的模板ID） */
    @Value("${wechat.subscribe-templates.remind:}")
    private String remindTemplateId;

    @Value("${wechat.subscribe-templates.violation:}")
    private String violationTemplateId;

    @Value("${wechat.subscribe-templates.ban:}")
    private String banTemplateId;

    @Value("${meeting.wechat-mock}")
    private boolean mock;

    private final WechatService wechatService;

    /**
     * 发送订阅消息（尽力而为，失败静默）
     *
     * @param openid     接收用户 openid
     * @param templateId 模板 ID
     * @param page       点击消息后跳转的小程序页面（如 pages/booking/my）
     * @param data       模板字段数据（key 对应模板字段名，value 为文本）
     */
    public void send(String openid, String templateId, String page, Map<String, String> data) {
        if (mock) {
            log.info("[订阅消息][mock] openid={} templateId={} page={} data={}", openid, templateId, page, data);
            return;
        }
        if (openid == null || openid.isEmpty() || templateId == null || templateId.isEmpty()) {
            log.warn("[订阅消息] openid 或 templateId 为空，跳过下发");
            return;
        }
        try {
            String accessToken = wechatService.getAccessTokenValue();
            if (accessToken == null) {
                log.warn("[订阅消息] 未获取到 access_token，跳过下发");
                return;
            }
            JSONObject body = JSONUtil.createObj()
                    .set("touser", openid)
                    .set("template_id", templateId)
                    .set("page", page == null ? "" : page)
                    .set("miniprogram_state", "formal")
                    .set("data", buildData(data));

            String url = SUBSCRIBE_SEND_URL + "?access_token=" + accessToken;
            try (HttpResponse resp = HttpRequest.post(url)
                    .header("Content-Type", "application/json")
                    .body(body.toString())
                    .timeout(5000)
                    .execute()) {
                JSONObject json = JSONUtil.parseObj(resp.body());
                int errcode = json.getInt("errcode", 0);
                if (errcode != 0) {
                    // 43101：用户拒绝/未订阅，属正常情况，只记 warn 不抛异常
                    log.warn("[订阅消息] 下发失败 errcode={} errmsg={}", errcode, json.getStr("errmsg"));
                }
            }
        } catch (Exception e) {
            log.warn("[订阅消息] 下发异常：{}", e.getMessage());
        }
    }

    /**
     * 已配置的模板 ID（去重、去空）。
     *
     * <p>给小程序端调用 {@code wx.requestSubscribeMessage} 用：
     * 订阅消息是「一次授权 = 一次下发额度」的机制，用户没在小程序里点过授权，
     * 后端就算有模板 ID 也发不出去（微信返回 43101）。因此模板 ID 必须下发到前端。</p>
     */
    public java.util.List<String> configuredTemplateIds() {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        addIfPresent(ids, remindTemplateId);
        addIfPresent(ids, violationTemplateId);
        addIfPresent(ids, banTemplateId);
        return new java.util.ArrayList<>(ids);
    }

    /**
     * 尚未配置模板的场景名，用于前端展示与启动日志，便于确认「到哪一步了」。
     */
    public java.util.List<String> missingScenes() {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (isBlank(remindTemplateId)) {
            missing.add("remind");
        }
        if (isBlank(violationTemplateId)) {
            missing.add("violation");
        }
        if (isBlank(banTemplateId)) {
            missing.add("ban");
        }
        return missing;
    }

    private void addIfPresent(java.util.Set<String> target, String id) {
        if (!isBlank(id)) {
            target.add(id.trim());
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 临近 30 分钟未签到提醒
     */
    public void sendRemind(String openid, String subject, String roomName, String startTime) {
        send(openid, remindTemplateId, "pages/booking/my", buildRemindData(subject, roomName, startTime));
    }

    /**
     * 违约通知
     */
    public void sendViolation(String openid, String subject, String roomName) {
        send(openid, violationTemplateId, "pages/booking/my", buildViolationData(subject, roomName));
    }

    /**
     * 禁止预约通知
     */
    public void sendBan(String openid, String until) {
        send(openid, banTemplateId, "pages/mine/mine", buildBanData(until));
    }

    private JSONObject buildData(Map<String, String> data) {
        JSONObject obj = JSONUtil.createObj();
        if (data != null) {
            for (Map.Entry<String, String> e : data.entrySet()) {
                obj.set(e.getKey(), JSONUtil.createObj().set("value", e.getValue()));
            }
        }
        return obj;
    }

    // 以下 buildXxxData 的字段名需与小程序后台模板的实际字段名一一对应，模板申请后再对齐
    private Map<String, String> buildRemindData(String subject, String roomName, String startTime) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("thing1", subject == null ? "" : subject);
        m.put("thing2", roomName == null ? "" : roomName);
        m.put("time3", startTime == null ? "" : startTime);
        return m;
    }

    private Map<String, String> buildViolationData(String subject, String roomName) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("thing1", subject == null ? "" : subject);
        m.put("thing2", roomName == null ? "" : roomName);
        return m;
    }

    private Map<String, String> buildBanData(String until) {
        Map<String, String> m = new java.util.HashMap<>();
        m.put("time1", until == null ? "" : until);
        return m;
    }
}
