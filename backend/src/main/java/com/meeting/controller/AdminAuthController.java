package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.dto.AdminLoginDTO;
import com.meeting.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

/**
 * 管理端认证接口
 */
@RestController
@RequestMapping("/api/admin/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AuthService authService;

    @GetMapping("/captcha")
    public Result<Map<String, String>> captcha() {
        return Result.success(authService.getCaptcha());
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Valid @RequestBody AdminLoginDTO dto) {
        return Result.success(authService.adminLogin(dto));
    }

    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.success();
    }
}
