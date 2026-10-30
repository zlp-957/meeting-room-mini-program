package com.meeting.common;

/**
 * 业务异常。
 *
 * <p><b>默认 code 必须是 400 而不是 500</b>：业务规则拦截（时段冲突、状态不允许、已封禁…）
 * 是「请求本身不合法」，属于 4xx；用 500 表达会把正常的业务拦截混进系统错误告警里，
 * 运维看到大量 5xx 会误判服务不健康，真实故障反而被淹没。</p>
 *
 * <p>确实需要区分的场景由调用方显式指定 code：</p>
 * <ul>
 *   <li>400 参数 / 业务规则不满足（默认）</li>
 *   <li>404 目标资源不存在</li>
 *   <li>409 与当前资源状态冲突（如会议室还有未结束预约时删除）</li>
 *   <li>429 频率 / 次数限制</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    /** 默认 400：业务拦截不是系统故障 */
    private Integer code = 400;

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }
}
