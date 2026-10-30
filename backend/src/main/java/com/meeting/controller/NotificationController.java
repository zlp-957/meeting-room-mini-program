package com.meeting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.Result;
import com.meeting.entity.Notification;
import com.meeting.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户端站内通知接口
 */
@RestController
@RequestMapping("/api/notification")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/list")
    public Result<Page<Notification>> list(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return Result.success(notificationService.page(page, size));
    }

    @GetMapping("/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        Map<String, Long> map = new HashMap<>();
        map.put("count", notificationService.unreadCount());
        return Result.success(map);
    }

    @PostMapping("/read/{id}")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(id);
        return Result.success();
    }

    @PostMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead();
        return Result.success();
    }
}
