package com.meeting.controller;

import com.meeting.common.BusinessException;
import com.meeting.common.Result;
import com.meeting.dto.BookingCreateDTO;
import com.meeting.dto.BookingSignDTO;
import com.meeting.dto.BookingSlotVO;
import com.meeting.entity.Booking;
import com.meeting.service.BookingService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.List;

/**
 * 用户端预约接口
 */
@RestController
@RequestMapping("/api/booking")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/create")
    public Result<Booking> create(@Valid @RequestBody BookingCreateDTO dto) {
        return Result.success(bookingService.create(dto));
    }

    @GetMapping("/my")
    public Result<List<Booking>> my(@RequestParam(required = false) Integer status) {
        return Result.success(bookingService.myBookings(status));
    }

    /** 单条详情：编辑页预填用，只返回本人的预约 */
    @GetMapping("/detail/{id}")
    public Result<Booking> detail(@PathVariable Long id) {
        return Result.success(bookingService.detail(id));
    }

    /**
     * 修改预约。
     *
     * <p>只允许改「待审批 / 已通过且未签到、未临近开始」的预约；
     * 改了时间或会议室后，已通过的预约会退回待审批重新审核。</p>
     */
    @PutMapping("/update/{id}")
    public Result<Booking> update(@PathVariable Long id, @Valid @RequestBody BookingCreateDTO dto) {
        return Result.success(bookingService.update(id, dto));
    }

    @PostMapping("/cancel/{id}")
    public Result<Void> cancel(@PathVariable Long id) {
        bookingService.cancel(id);
        return Result.success();
    }

    /**
     * 扫码签到。
     *
     * <p>{@code required = false} + 手工判空：老版本小程序调用时不会带请求体，
     * 交给 Bean Validation 只会返回框架级错误，用户看不懂。这里给出可行动的提示。</p>
     */
    @PostMapping("/sign/{id}")
    public Result<Booking> sign(@PathVariable Long id,
                                @RequestBody(required = false) BookingSignDTO dto) {
        if (dto == null || dto.getCode() == null || dto.getCode().trim().isEmpty()) {
            throw new BusinessException(400, "请使用最新版本小程序，扫描会议室门前的二维码签到");
        }
        return Result.success(bookingService.sign(id, dto.getCode()));
    }

    /**
     * 某会议室某日已占用时段。
     *
     * <p>返回专用 VO（只含时间段与状态），不返回预约实体 —— 这个接口任何登录用户都能调，
     * 返回实体会把别人的会议主题、审批人、签到情况一起泄露。</p>
     */
    @GetMapping("/available/{roomId}")
    public Result<List<BookingSlotVO>> available(@PathVariable Long roomId,
                                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                 @RequestParam(required = false) Long excludeId) {
        return Result.success(bookingService.dayBookings(roomId, date, excludeId));
    }
}
