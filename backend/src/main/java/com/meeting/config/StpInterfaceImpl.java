package com.meeting.config;

import cn.dev33.satoken.stp.StpInterface;
import com.meeting.common.LoginHelper;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色数据源
 *
 * <p>P0-1：原项目使用 {@code StpUtil.checkRole("admin")} 鉴权，但没有实现 StpInterface，
 * Sa-Token 默认返回空角色列表，导致管理员登录后访问任何后台接口都是 403。
 * 这里按 loginId 前缀返回对应角色。</p>
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        String id = String.valueOf(loginId);
        if (id.startsWith(LoginHelper.ADMIN_PREFIX)) {
            return Collections.singletonList("admin");
        }
        if (id.startsWith(LoginHelper.USER_PREFIX)) {
            return Collections.singletonList("user");
        }
        return Collections.emptyList();
    }
}
