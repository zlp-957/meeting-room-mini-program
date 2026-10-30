package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.meeting.entity.Booking;
import com.meeting.mapper.BookingMapper;
import com.meeting.mapper.MeetingRoomMapper;
import com.meeting.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final BookingMapper bookingMapper;
    private final MeetingRoomMapper roomMapper;
    private final SysUserMapper userMapper;

    public Map<String, Object> stats() {
        Map<String, Object> result = new HashMap<>();

        // 总数统计
        Long roomCount = roomMapper.selectCount(null);
        Long userCount = userMapper.selectCount(null);
        Long bookingCount = bookingMapper.selectCount(null);
        Long pendingCount = bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>().eq(Booking::getStatus, 0));

        // P1-8：「今日预约」原先只有 >= 今天 0 点，把未来所有预约都算了进来。
        // 这里补齐上界「明天 0 点」，并排除已取消的记录。
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime tomorrowStart = LocalDate.now().plusDays(1).atStartOfDay();
        Long todayCount = bookingMapper.selectCount(
                new LambdaQueryWrapper<Booking>()
                        .ge(Booking::getStartTime, todayStart)
                        .lt(Booking::getStartTime, tomorrowStart)
                        .ne(Booking::getStatus, 3));

        result.put("roomCount", roomCount);
        result.put("userCount", userCount);
        result.put("bookingCount", bookingCount);
        result.put("pendingCount", pendingCount);
        result.put("todayCount", todayCount);

        return result;
    }
}
