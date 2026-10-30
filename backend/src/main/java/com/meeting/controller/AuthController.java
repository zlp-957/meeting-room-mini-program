package com.meeting.controller;

import com.meeting.common.Result;
import com.meeting.dto.BindPhoneDTO;
import com.meeting.dto.WxLoginDTO;
import com.meeting.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

/**
 * 用户端认证接口
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/wx-login")
    public Result<Map<String, Object>> wxLogin(@Valid @RequestBody WxLoginDTO dto) {
        return Result.success(authService.wxLogin(dto));
    }

    @PostMapping("/bind-phone")
    public Result<Map<String, Object>> bindPhone(@Valid @RequestBody BindPhoneDTO dto) {
        return Result.success(authService.bindPhone(dto));
    }
}
