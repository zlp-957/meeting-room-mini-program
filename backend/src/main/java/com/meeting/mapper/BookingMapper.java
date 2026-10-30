package com.meeting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.meeting.dto.BookingSlotVO;
import com.meeting.entity.Booking;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface BookingMapper extends BaseMapper<Booking> {

    /**
     * 冲突检测：查询同一会议室同一时间段内是否存在有效预约
     *
     * <p>P0-3：审批时需要排除当前预约自身，否则待审批记录会与自己的时段冲突，
     * 导致审批永远失败。{@code excludeId} 为空时不做排除。</p>
     *
     * <p>{@code gapMinutes}：同会议室相邻两场预约之间要求的最小间隔（分钟）。
     * 冲突判定为新预约 [start, end) 与已有 [s, e) 的间隔不足 gapMinutes 即冲突：
     * {@code start < e + gap} 且 {@code end > s - gap}，两端都缓冲。</p>
     */
    @Select("SELECT COUNT(*) FROM booking " +
            "WHERE room_id = #{roomId} " +
            "AND status IN (0, 1) " +
            "AND deleted = 0 " +
            "AND start_time < DATE_ADD(#{endTime}, INTERVAL #{gapMinutes} MINUTE) " +
            "AND end_time > DATE_SUB(#{startTime}, INTERVAL #{gapMinutes} MINUTE) " +
            "AND (#{excludeId,jdbcType=BIGINT} IS NULL OR id != #{excludeId,jdbcType=BIGINT})")
    int countConflict(@Param("roomId") Long roomId,
                      @Param("startTime") LocalDateTime startTime,
                      @Param("endTime") LocalDateTime endTime,
                      @Param("gapMinutes") int gapMinutes,
                      @Param("excludeId") Long excludeId);

    /**
     * 查询某会议室某日已占用的时间段（用于前端展示）。
     *
     * <p><b>只 SELECT 三个字段，不 SELECT *</b>：这个接口所有登录用户都能调，
     * 返回 Booking 实体会连带把别人预约的 userId / 会议主题 / 审批人 / 签到时间一起吐出去，
     * 等于提供一个「按会议室枚举他人会议」的接口。用专用 VO 从 SQL 层面就杜绝了误加字段。</p>
     *
     * <p>{@code excludeId}：编辑已有预约时要排除自身，否则用户会看到自己的时段是「已占用」，
     * 无法再次选中原时段做微调。</p>
     */
    @Select("SELECT room_id, start_time, end_time, status FROM booking " +
            "WHERE room_id = #{roomId} " +
            "AND status IN (0, 1) " +
            "AND deleted = 0 " +
            "AND start_time < #{dayEnd} " +
            "AND end_time > #{dayStart} " +
            "AND (#{excludeId,jdbcType=BIGINT} IS NULL OR id != #{excludeId,jdbcType=BIGINT}) " +
            "ORDER BY start_time")
    List<BookingSlotVO> listDaySlots(@Param("roomId") Long roomId,
                                     @Param("dayStart") LocalDateTime dayStart,
                                     @Param("dayEnd") LocalDateTime dayEnd,
                                     @Param("excludeId") Long excludeId);


    /**
     * 统计会议室在指定时间之后的有效预约数量（P2-12：删除会议室前的校验）
     */
    @Select("SELECT COUNT(*) FROM booking " +
            "WHERE room_id = #{roomId} " +
            "AND status IN (0, 1) " +
            "AND deleted = 0 " +
            "AND end_time > #{now}")
    int countFutureBookings(@Param("roomId") Long roomId, @Param("now") LocalDateTime now);

    /**
     * 管理端用户详情：按状态聚合某用户的预约数量（单行返回，无记录时 COUNT 为 0、SUM 为 null）
     *
     * <p>别名刻意不带下划线，避免 Map 结果集在 map-underscore-to-camel-case 下键名变化。</p>
     */
    @Select("SELECT COUNT(*) AS total, " +
            "SUM(status = 0) AS pending, " +
            "SUM(status = 1) AS approved, " +
            "SUM(status = 2) AS rejected, " +
            "SUM(status = 3) AS canceled, " +
            "SUM(status = 4) AS violated " +
            "FROM booking WHERE user_id = #{userId} AND deleted = 0")
    Map<String, Object> statByUser(@Param("userId") Long userId);

    /**
     * 管理端用户详情：某用户最近若干条预约（带会议室名，自动映射到 Booking.roomName）
     */
    @Select("SELECT b.*, r.name AS room_name FROM booking b " +
            "LEFT JOIN meeting_room r ON r.id = b.room_id " +
            "WHERE b.user_id = #{userId} AND b.deleted = 0 " +
            "ORDER BY b.start_time DESC LIMIT #{limit}")
    List<Booking> listRecentByUser(@Param("userId") Long userId, @Param("limit") int limit);
}
