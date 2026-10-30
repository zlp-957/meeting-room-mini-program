package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.BusinessException;
import com.meeting.common.LoginHelper;
import com.meeting.entity.Notification;
import com.meeting.mapper.NotificationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内通知服务
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper notificationMapper;

    /**
     * 插入一条站内通知
     */
    public void push(Long userId, Integer type, String title, String content, Long bizId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setBizId(bizId);
        n.setIsRead(0);
        notificationMapper.insert(n);
    }

    /**
     * 当前用户通知列表（分页）
     */
    public Page<Notification> page(int page, int size) {
        Long userId = LoginHelper.userId();
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), 100);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime);
        return notificationMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 标记单条已读
     */
    public void markRead(Long id) {
        Long userId = LoginHelper.userId();
        int rows = notificationMapper.markRead(id, userId);
        if (rows == 0) {
            throw new BusinessException("通知不存在");
        }
    }

    /**
     * 全部标记已读
     */
    public void markAllRead() {
        Long userId = LoginHelper.userId();
        notificationMapper.markAllRead(userId);
    }

    /**
     * 未读数
     */
    public long unreadCount() {
        Long userId = LoginHelper.userId();
        return notificationMapper.countUnread(userId);
    }

    /**
     * 当前用户最新几条通知（供「我的」页红点展示，可选）
     */
    public List<Notification> recent(int limit) {
        Long userId = LoginHelper.userId();
        limit = Math.min(Math.max(1, limit), 50);
        LambdaQueryWrapper<Notification> wrapper = new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .last("LIMIT " + limit);
        return notificationMapper.selectList(wrapper);
    }
}
