package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.entity.MeetingRoom;
import com.meeting.service.RoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户端会议室接口
 */
@RestController
@RequestMapping("/api/room")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;

    @GetMapping("/list")
    public Result<List<MeetingRoom>> list() {
        return Result.success(roomService.listEnabled());
    }

    @GetMapping("/{id}")
    public Result<MeetingRoom> detail(@PathVariable Long id) {
        return Result.success(roomService.detail(id));
    }
}
