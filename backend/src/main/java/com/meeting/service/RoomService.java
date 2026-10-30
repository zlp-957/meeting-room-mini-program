package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.BusinessException;
import com.meeting.common.SignCodeUtil;
import com.meeting.dto.RoomSignCodeVO;
import com.meeting.entity.MeetingRoom;
import com.meeting.mapper.BookingMapper;
import com.meeting.mapper.MeetingRoomMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RoomService {

    /** 分页单页上限（P2-16） */
    private static final int MAX_PAGE_SIZE = 100;

    private final MeetingRoomMapper roomMapper;
    private final BookingMapper bookingMapper;

    /**
     * 预约时段粒度（分钟），与 BookingService 共用同一配置。
     *
     * <p>会议室开放时间必须是它的整数倍，否则时间轴起点与全天的 15 分钟网格错位，
     * 用户永远选不出一个合法时段（详见 {@link #validate(MeetingRoom)}）。</p>
     */
    @org.springframework.beans.factory.annotation.Value("${meeting.slot-minutes:15}")
    private int slotMinutes;

    /**
     * 会议室列表（用户端，仅启用的）
     */
    public List<MeetingRoom> listEnabled() {
        return roomMapper.selectList(new LambdaQueryWrapper<MeetingRoom>()
                .eq(MeetingRoom::getStatus, 1)
                .orderByAsc(MeetingRoom::getId));
    }

    /**
     * 会议室详情（用户端）
     *
     * <p>P2-1：停用的会议室不应再通过 ID 直接访问。</p>
     */
    public MeetingRoom detail(Long id) {
        MeetingRoom room = roomMapper.selectById(id);
        if (room == null || room.getStatus() == null || room.getStatus() == 0) {
            throw new BusinessException(404, "会议室不存在或已下线");
        }
        return room;
    }

    /**
     * 管理端：分页列表
     */
    public Page<MeetingRoom> page(int page, int size, String name) {
        // P2-16：分页参数兜底
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        LambdaQueryWrapper<MeetingRoom> wrapper = new LambdaQueryWrapper<MeetingRoom>()
                .orderByAsc(MeetingRoom::getId);
        if (name != null && !name.isEmpty()) {
            wrapper.like(MeetingRoom::getName, name);
        }
        return roomMapper.selectPage(new Page<>(page, size), wrapper);
    }

    /**
     * 管理端：新增
     */
    public void add(MeetingRoom room) {
        room.setId(null);
        validate(room);
        // 签到码由后端生成，绝不接受前端传入（否则管理员可把两间房设成同一个码）
        room.setSignCode(SignCodeUtil.generate());
        room.setSignCodeTime(LocalDateTime.now());
        roomMapper.insert(room);
    }

    /**
     * 管理端：修改
     *
     * <p>显式把签到码置 null：MyBatis-Plus 默认 NOT_NULL 更新策略会跳过 null 字段，
     * 效果就是"这两列不参与本次更新"。不置 null 的话，编辑会议室时前端传来的
     * 空 signCode 会把已有签到码覆盖掉，贴好的二维码当场失效。</p>
     *
     * <p>先校验存在性：{@code updateById} 影响 0 行不会报错，
     * 早期实现里改一个不存在的 ID 也返回 200，管理端会显示「保存成功」但什么都没发生。</p>
     */
    public void update(MeetingRoom room) {
        if (room.getId() == null) {
            throw new BusinessException(400, "会议室 ID 不能为空");
        }
        if (roomMapper.selectById(room.getId()) == null) {
            throw new BusinessException(404, "会议室不存在或已被删除");
        }
        validate(room);
        room.setSignCode(null);
        room.setSignCodeTime(null);
        roomMapper.updateById(room);
    }

    /**
     * 管理端：删除
     *
     * <p>P2-12：删除前校验是否还有未结束的预约，避免产生孤儿预约。</p>
     */
    public void delete(Long id) {
        MeetingRoom room = roomMapper.selectById(id);
        if (room == null) {
            throw new BusinessException(404, "会议室不存在或已被删除");
        }
        int future = bookingMapper.countFutureBookings(id, LocalDateTime.now());
        if (future > 0) {
            // 409：不是参数错，而是当前资源状态不允许 —— 与「记录不存在」区分开，前端可提示「改为停用」
            throw new BusinessException(409, "该会议室还有 " + future + " 条未结束的预约，请先处理或改为停用");
        }
        roomMapper.deleteById(id);
    }

    /**
     * 查看会议室签到码（管理端）。
     *
     * <p>库里为空时现场补一个（懒生成兜底）：即使迁移脚本漏跑、或有人手工 INSERT
     * 了一条会议室，也不会出现「打开签到码弹窗一片空白」。</p>
     */
    public RoomSignCodeVO signCode(Long id) {
        MeetingRoom room = requireRoom(id);
        if (room.getSignCode() == null || room.getSignCode().trim().isEmpty()) {
            room = ensureSignCode(room);
        }
        return toSignCodeVO(room);
    }

    /**
     * 重新生成签到码。旧码立即作废（库里 sign_code 变了，旧二维码查不到房间）。
     */
    public RoomSignCodeVO resetSignCode(Long id) {
        requireRoom(id);
        LocalDateTime now = LocalDateTime.now();
        String code = SignCodeUtil.generate();
        roomMapper.update(null, new LambdaUpdateWrapper<MeetingRoom>()
                .eq(MeetingRoom::getId, id)
                .set(MeetingRoom::getSignCode, code)
                .set(MeetingRoom::getSignCodeTime, now));
        MeetingRoom room = roomMapper.selectById(id);
        return toSignCodeVO(room);
    }

    /** 补齐缺失的签到码并落库 */
    private MeetingRoom ensureSignCode(MeetingRoom room) {
        LocalDateTime now = LocalDateTime.now();
        String code = SignCodeUtil.generate();
        roomMapper.update(null, new LambdaUpdateWrapper<MeetingRoom>()
                .eq(MeetingRoom::getId, room.getId())
                .set(MeetingRoom::getSignCode, code)
                .set(MeetingRoom::getSignCodeTime, now));
        room.setSignCode(code);
        room.setSignCodeTime(now);
        return room;
    }

    private MeetingRoom requireRoom(Long id) {
        MeetingRoom room = roomMapper.selectById(id);
        if (room == null) {
            throw new BusinessException(404, "会议室不存在");
        }
        return room;
    }

    private RoomSignCodeVO toSignCodeVO(MeetingRoom room) {
        RoomSignCodeVO vo = new RoomSignCodeVO();
        vo.setRoomId(room.getId());
        vo.setRoomName(room.getName());
        vo.setLocation(room.getLocation());
        vo.setSignCode(room.getSignCode());
        // 格式只在 SignCodeUtil 一处定义，管理后台只负责渲染，不参与拼接
        vo.setQrContent(SignCodeUtil.buildContent(room.getId(), room.getSignCode()));
        vo.setSignCodeTime(room.getSignCodeTime());
        return vo;
    }

    /**
     * P1-12：字段校验（长度、容量、开放时间）
     */
    private void validate(MeetingRoom room) {
        if (room.getName() == null || room.getName().trim().isEmpty()) {
            throw new BusinessException(400, "会议室名称不能为空");
        }
        room.setName(room.getName().trim());
        if (room.getName().length() > 64) {
            throw new BusinessException(400, "会议室名称不能超过 64 个字符");
        }
        if (room.getLocation() != null && room.getLocation().length() > 128) {
            throw new BusinessException(400, "位置不能超过 128 个字符");
        }
        if (room.getRemark() != null && room.getRemark().length() > 255) {
            throw new BusinessException(400, "备注不能超过 255 个字符");
        }
        if (room.getCapacity() == null || room.getCapacity() <= 0) {
            throw new BusinessException(400, "容纳人数必须大于 0");
        }
        if (room.getOpenTime() != null && room.getCloseTime() != null
                && !room.getCloseTime().isAfter(room.getOpenTime())) {
            throw new BusinessException(400, "打烊时间必须晚于开门时间");
        }
        // 开放时间必须与预约粒度对齐。
        // 反例：管理端把开放时间设成 08:07，而预约时间必须落在 15 分钟网格上，
        // 于是每天第一场可预约时段是 08:15，08:07~08:15 永远空着；
        // 更糟的是如果打烊时间也错位，端到端可能连一个合法时段都算不出来，用户会以为系统坏了。
        assertSlotAligned(room.getOpenTime(), "开放开始时间");
        assertSlotAligned(room.getCloseTime(), "开放结束时间");
    }

    /** 校验时间点落在 slotMinutes 网格上（秒必须为 0，分钟必须是粒度的整数倍） */
    private void assertSlotAligned(java.time.LocalTime time, String label) {
        if (time == null) {
            return;
        }
        if (time.getSecond() != 0 || time.getMinute() % slotMinutes != 0) {
            throw new BusinessException(400, label + "必须是 " + slotMinutes
                    + " 分钟的整数倍（如 08:00、08:15、08:30、08:45）");
        }
    }
}
