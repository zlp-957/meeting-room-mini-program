package com.meeting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.Result;
import com.meeting.entity.Announcement;
import com.meeting.service.AnnouncementService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端公告接口
 */
@RestController
@RequestMapping("/api/admin/announcement")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcementService;

    /** 分页查询（原 /list 一次性返回全量，已改为分页） */
    @GetMapping("/page")
    public Result<Page<Announcement>> page(@RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        return Result.success(announcementService.page(page, size));
    }

    @PostMapping
    public Result<Void> add(@RequestBody Announcement a) {
        announcementService.add(a);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody Announcement a) {
        a.setId(id);
        announcementService.update(a);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        announcementService.delete(id);
        return Result.success();
    }
}
