package com.meeting.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.meeting.common.Result;
import com.meeting.dto.UserBanDTO;
import com.meeting.dto.UserDetailVO;
import com.meeting.dto.UserListVO;
import com.meeting.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端用户接口
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    /** 用户列表（不返回 openid，见 UserListVO 说明） */
    @GetMapping("/page")
    public Result<Page<UserListVO>> page(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size,
                                         @RequestParam(required = false) String keyword) {
        return Result.success(userService.page(page, size, keyword));
    }

    /**
     * 用户详情（路径用 /detail/{id}，避免与 /page 的路径变量歧义）
     */
    @GetMapping("/detail/{id}")
    public Result<UserDetailVO> detail(@PathVariable Long id) {
        return Result.success(userService.detail(id));
    }

    /**
     * 封禁用户：body {"days": 7}；days 为 null 或 0 表示永久禁用
     */
    @PostMapping("/ban/{id}")
    public Result<Void> ban(@PathVariable Long id, @RequestBody(required = false) UserBanDTO dto) {
        userService.ban(id, dto == null ? null : dto.getDays());
        return Result.success();
    }

    @PostMapping("/unban/{id}")
    public Result<Void> unban(@PathVariable Long id) {
        userService.unban(id);
        return Result.success();
    }

    /**
     * 解除永久禁用。
     *
     * <p>与 /unban 分开：/unban 只解限时封禁，遇到永久禁用会拒绝并提示走本接口，
     * 防止一次误点就把禁用账号放回来。</p>
     */
    @PostMapping("/enable/{id}")
    public Result<Void> enable(@PathVariable Long id) {
        userService.enable(id);
        return Result.success();
    }
}
