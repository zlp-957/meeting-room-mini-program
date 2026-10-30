package com.meeting.service;

import com.meeting.common.BusinessException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 昵称合规校验单元测试
 *
 * <p>纯逻辑校验，不启动 Spring 容器，直接 new 出被测对象。</p>
 */
class NicknameCheckTest {

    private static NicknameCheckService service;

    @BeforeAll
    static void setUp() {
        service = new NicknameCheckService();
        service.loadWords();
    }

    @ParameterizedTest
    @ValueSource(strings = {"张李鹏", "小明同学", "Alice_2026", "会议达人", "Tom-Hanks", "产品 经理"})
    @DisplayName("正常昵称应通过，并去掉首尾空格")
    void shouldPass(String nickname) {
        assertEquals(nickname, service.check(nickname));
    }

    @Test
    @DisplayName("首尾空格会被清理")
    void shouldTrim() {
        assertEquals("张李鹏", service.check("  张李鹏  "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"傻 逼", "傻*逼", "傻Ｂ", "智障啊", "管理员", "官方客服"})
    @DisplayName("敏感词与冒充官方的变体应被拦截")
    void shouldRejectSensitive(String nickname) {
        assertThrows(BusinessException.class, () -> service.check(nickname));
    }

    @ParameterizedTest
    @ValueSource(strings = {"加微信abc", "13800138000", "12345678", "http://x.com", "测试@符号", "aaaaaa", "a"})
    @DisplayName("引流 / 联系方式 / 纯数字 / 非法字符 / 灌水 / 过短应被拦截")
    void shouldRejectInvalidFormat(String nickname) {
        assertThrows(BusinessException.class, () -> service.check(nickname));
    }

    @Test
    @DisplayName("超长昵称应被拦截")
    void shouldRejectTooLong() {
        // 23 个字符，超过 20 的上限
        assertThrows(BusinessException.class, () -> service.check("超长昵称测试一二三四五六七八九十甲乙丙丁戊己庚"));
    }

    @Test
    @DisplayName("空昵称应被拦截")
    void shouldRejectBlank() {
        assertThrows(BusinessException.class, () -> service.check("   "));
    }
}
