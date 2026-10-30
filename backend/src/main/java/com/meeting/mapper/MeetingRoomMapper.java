package com.meeting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.meeting.entity.MeetingRoom;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MeetingRoomMapper extends BaseMapper<MeetingRoom> {

    /**
     * 对会议室行加排他锁（P2-11）
     *
     * <p>创建预约时在同一事务内先锁住会议室，把「查冲突 → 插入」串行化，
     * 避免并发下两条请求同时通过冲突检测。</p>
     */
    @Select("SELECT id FROM meeting_room WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    Long lockById(@Param("id") Long id);

    /**
     * 按签到码查会议室。
     *
     * <p>扫码签到时的权威查询入口。<b>只用 sign_code 查，绝不用二维码里的 roomId 查</b>
     * —— roomId 是用户可读可改的明文，拿它查库等于允许「改个数字就签别的房间」。
     * sign_code 是 128 bit 随机值，才是真正的凭据。</p>
     *
     * <p>逻辑删除由 SQL 显式带上：这是自定义 SQL，MyBatis-Plus 不会自动追加
     * {@code deleted = 0}，漏掉会导致已删除会议室仍能签到。</p>
     */
    @Select("SELECT * FROM meeting_room WHERE sign_code = #{signCode} AND deleted = 0 LIMIT 1")
    MeetingRoom selectBySignCode(@Param("signCode") String signCode);
}
