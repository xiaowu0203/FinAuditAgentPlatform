package com.finaudit.tenant.controller;

import com.finaudit.starter.web.exception.BizException;
import com.finaudit.starter.web.result.R;
import com.finaudit.tenant.pojo.dto.LoginRequest;
import com.finaudit.tenant.pojo.vo.LoginVO;
import com.finaudit.tenant.pojo.vo.UserInfoVO;
import com.finaudit.tenant.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：登录（白名单，网关放行）/ 当前用户信息。
 */
@Tag(name = "认证", description = "登录 / 当前用户信息")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "登录", description = "校验用户名密码，签发 JWT")
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginRequest request) {
        return R.success(authService.login(request));
    }

    @Operation(summary = "当前用户信息", description = "返回当前登录用户信息与角色（需经网关注入 X-User-Id）")
    @GetMapping("/me")
    public R<UserInfoVO> me(@RequestHeader(name = "X-User-Id", required = false) Long userId) {
        if (userId == null) {
            throw new BizException("缺少用户标识 X-User-Id，请通过网关访问");
        }
        return R.success(authService.me(userId));
    }

    @Operation(summary = "登出", description = "作废当前 token（jti 来自网关注入的 X-Jwt-Jti 头），此后该 token 访问接口返回 401")
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(name = "X-Jwt-Jti", required = false) String jti) {
        if (jti == null || jti.isBlank()) {
            throw new BizException("缺少 token 标识 X-Jwt-Jti，请通过网关访问");
        }
        authService.logout(jti.trim());
        return R.success();
    }
}
