package com.meeting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.BusinessException;
import com.meeting.entity.Announcement;
import com.meeting.mapper.AnnouncementMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /** 分页单页上限，与其它管理端列表保持一致 */
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 公告正文长度上限。
     *
     * <p>数据库列是 TEXT，MySQL 的 TEXT 上限是 65535 <b>字节</b>，utf8mb4 下一个汉字占 3~4 字节，
     * 所以实际只能放约 1.6 万汉字。不在这里拦截，用户提交长文会撞上数据库异常变成 500，
     * 既看不懂也无法自查。取 20000 字符：小于数据库上限，又远大于任何正常公告。</p>
     */
    private static final int MAX_CONTENT_LENGTH = 20000;

    private final AnnouncementMapper announcementMapper;

    /**
     * 公告列表（用户端，仅上线）
     */
    public List<Announcement> listEnabled() {
        return announcementMapper.selectList(new LambdaQueryWrapper<Announcement>()
                .eq(Announcement::getStatus, 1)
                .orderByDesc(Announcement::getCreateTime));
    }

    /**
     * 公告详情（用户端）
     *
     * <p>下线的公告不应再通过 ID 直接访问。</p>
     */
    public Announcement detail(Long id) {
        Announcement a = announcementMapper.selectById(id);
        if (a == null || a.getStatus() == null || a.getStatus() == 0) {
            throw new BusinessException(404, "公告不存在或已下线");
        }
        return a;
    }

    /**
     * 管理端：分页查询全部公告
     *
     * <p>原先一次性拉全量，公告多了以后管理端首屏会明显变慢，接口也没有上限可被拖垮。</p>
     */
    public Page<Announcement> page(int page, int size) {
        page = Math.max(1, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return announcementMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Announcement>().orderByDesc(Announcement::getCreateTime));
    }

    /**
     * 管理端：发布
     */
    public void add(Announcement a) {
        a.setId(null);
        validate(a);
        if (a.getStatus() == null) {
            a.setStatus(1);
        }
        announcementMapper.insert(a);
    }

    /**
     * 管理端：修改
     *
     * <p>先判存在性：{@code updateById} 影响 0 行不报错，改一个不存在的 ID 会「静默成功」。</p>
     */
    public void update(Announcement a) {
        if (a.getId() == null) {
            throw new BusinessException(400, "公告 ID 不能为空");
        }
        if (announcementMapper.selectById(a.getId()) == null) {
            throw new BusinessException(404, "公告不存在或已被删除");
        }
        validate(a);
        announcementMapper.updateById(a);
    }

    /**
     * 管理端：删除
     */
    public void delete(Long id) {
        if (announcementMapper.selectById(id) == null) {
            throw new BusinessException(404, "公告不存在或已被删除");
        }
        announcementMapper.deleteById(id);
    }

    /**
     * 公告字段校验
     */
    private void validate(Announcement a) {
        if (a.getTitle() == null || a.getTitle().trim().isEmpty()) {
            throw new BusinessException(400, "公告标题不能为空");
        }
        a.setTitle(a.getTitle().trim());
        if (a.getTitle().length() > 128) {
            throw new BusinessException(400, "公告标题不能超过 128 个字符");
        }
        if (a.getContent() != null && a.getContent().length() > MAX_CONTENT_LENGTH) {
            throw new BusinessException(400,
                    "公告内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符（当前 " + a.getContent().length() + " 个）");
        }
    }
}
