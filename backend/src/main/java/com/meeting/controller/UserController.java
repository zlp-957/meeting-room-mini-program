package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.dto.UpdateNicknameDTO;
import com.meeting.service.AuthService;
import com.meeting.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户个人资料接口（昵称 / 头像）
 *
 * <p>放在 {@code /api/user/**} 而不是 {@code /api/auth/**}，是因为 WebConfig 里
 * {@code /api/auth/**} 是整体放行的公开路径（登录、验证码需要），
 * 而改昵称、换头像必须拿到登录态，走这一组受保护的路径。</p>
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final AuthService authService;
    private final FileStorageService fileStorageService;

    /** 当前登录用户信息（“我的”页进入时刷新，保证换设备后昵称头像同步） */
    @GetMapping("/profile")
    public Result<Map<String, Object>> profile() {
        return Result.success(authService.getProfile());
    }

    /** 修改昵称（长度、字符集、敏感词、冒充官方、广告引流由服务层校验） */
    @PutMapping("/nickname")
    public Result<Map<String, Object>> updateNickname(@Valid @RequestBody UpdateNicknameDTO dto) {
        return Result.success(authService.updateNickname(dto));
    }

    /**
     * 上传 / 更换头像
     *
     * <p>一步到位：图片落盘 → 更新 user.avatar → 删除旧图 → 返回最新 userInfo。
     * 前端拿到 userInfo 直接覆盖本地缓存即可。</p>
     */
    @PostMapping("/avatar")
    public Result<Map<String, Object>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String url = fileStorageService.saveAvatar(file);
        Map<String, Object> userInfo = authService.updateAvatar(url);

        Map<String, Object> result = new HashMap<>();
        result.put("url", url);
        result.put("userInfo", userInfo);
        return Result.success(result);
    }
}
