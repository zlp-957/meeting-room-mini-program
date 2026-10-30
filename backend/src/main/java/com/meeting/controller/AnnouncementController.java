package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.entity.Announcement;
import com.meeting.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户端公告接口
 */
@RestController
@RequestMapping("/api/announcement")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @GetMapping("/list")
    public Result<List<Announcement>> list() {
        return Result.success(announcementService.listEnabled());
    }

    @GetMapping("/{id}")
    public Result<Announcement> detail(@PathVariable Long id) {
        return Result.success(announcementService.detail(id));
    }
}
