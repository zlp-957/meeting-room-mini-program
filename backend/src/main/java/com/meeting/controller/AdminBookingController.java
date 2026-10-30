package com.meeting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.Result;
import com.meeting.entity.Booking;
import com.meeting.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 管理端预约接口
 */
@RestController
@RequestMapping("/api/admin/booking")
@RequiredArgsConstructor
public class AdminBookingController {

    private final BookingService bookingService;

    @GetMapping("/page")
    public Result<Page<Booking>> page(@RequestParam(required = false) Integer status,
                                      @RequestParam(required = false) Long roomId,
                                      @RequestParam(required = false) Integer signState,
                                      @RequestParam(required = false) String keyword,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "10") int size) {
        return Result.success(bookingService.page(status, roomId, signState, keyword, page, size));
    }

    @PostMapping("/approve/{id}")
    public Result<Void> approve(@PathVariable Long id) {
        bookingService.approve(id);
        return Result.success();
    }

    @PostMapping("/reject/{id}")
    public Result<Void> reject(@PathVariable Long id, @RequestBody Map<String, String> body) {
        bookingService.reject(id, body.get("reason"));
        return Result.success();
    }

    /**
     * 代用户取消预约（释放时段）。
     *
     * <p>用户侧「开始前 2 小时不可取消」是给用户的约束，管理员需要一条兜底通道释放被误占的会议室。</p>
     */
    @PostMapping("/cancel/{id}")
    public Result<Void> cancel(@PathVariable Long id,
                               @RequestBody(required = false) Map<String, String> body) {
        bookingService.adminCancel(id, body == null ? null : body.get("reason"));
        return Result.success();
    }
}
