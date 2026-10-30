package com.meeting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.Result;
import com.meeting.dto.RoomSignCodeVO;
import com.meeting.entity.MeetingRoom;
import com.meeting.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端会议室接口
 */
@RestController
@RequestMapping("/api/admin/room")
@RequiredArgsConstructor
public class AdminRoomController {

    private final RoomService roomService;

    @GetMapping("/page")
    public Result<Page<MeetingRoom>> page(@RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int size,
                                          @RequestParam(required = false) String name) {
        return Result.success(roomService.page(page, size, name));
    }

    @PostMapping
    public Result<Void> add(@RequestBody MeetingRoom room) {
        roomService.add(room);
        return Result.success();
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody MeetingRoom room) {
        room.setId(id);
        roomService.update(room);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        roomService.delete(id);
        return Result.success();
    }

    /**
     * 查看会议室签到码（含二维码内容）。
     *
     * <p>签到码本身在 {@code MeetingRoom} 上是 {@code @JsonIgnore} 的，
     * 此处通过专用 VO 下发，避免公开接口 {@code /api/room/list} 泄漏。</p>
     */
    @GetMapping("/sign-code/{id}")
    public Result<RoomSignCodeVO> signCode(@PathVariable Long id) {
        return Result.success(roomService.signCode(id));
    }

    /**
     * 重新生成签到码：已张贴的旧二维码立即失效。
     *
     * <p>用于贴纸污损、会议室换位置、码被外传需要止损的场景。</p>
     */
    @PostMapping("/sign-code/{id}/reset")
    public Result<RoomSignCodeVO> resetSignCode(@PathVariable Long id) {
        return Result.success(roomService.resetSignCode(id));
    }
}
