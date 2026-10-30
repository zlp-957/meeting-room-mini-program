package com.meeting.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotRoleException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import javax.validation.ConstraintViolation;
import javax.validation.ConstraintViolationException;
import java.util.stream.Collectors;

/**
 * 全局异常处理
 *
 * <p>原则：客户端能修的问题返回 4xx 并给出可行动的提示，只有真正没预料到的异常才返回 500。
 * 500 里不返回异常堆栈或 SQL 片段，避免把内部结构暴露给终端用户。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusinessException(BusinessException e) {
        // 4xx 属于预期内的业务拦截，用 info/warn 级别；只有 5xx 才算需要排查的错误
        Integer code = e.getCode() == null ? 400 : e.getCode();
        if (code >= 500) {
            log.error("业务异常(5xx) code={} msg={}", code, e.getMessage());
        } else {
            log.info("业务拦截 code={} msg={}", code, e.getMessage());
        }
        return Result.error(code, e.getMessage());
    }

    @ExceptionHandler(NotLoginException.class)
    public Result<Void> handleNotLoginException(NotLoginException e) {
        return Result.error(401, "未登录或登录已过期");
    }

    @ExceptionHandler(NotRoleException.class)
    public Result<Void> handleNotRoleException(NotRoleException e) {
        return Result.error(403, "无权限访问");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidException(Exception e) {
        FieldError fieldError = null;
        if (e instanceof MethodArgumentNotValidException) {
            fieldError = ((MethodArgumentNotValidException) e).getBindingResult().getFieldError();
        } else if (e instanceof BindException) {
            fieldError = ((BindException) e).getBindingResult().getFieldError();
        }
        String msg = fieldError != null ? fieldError.getDefaultMessage() : "参数校验失败";
        return Result.error(400, msg);
    }

    /**
     * 请求体解析失败（例如时间格式不正确）：返回 400 而不是 500
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.info("请求参数解析失败: {}", e.getMessage());
        return Result.error(400, "请求参数格式不正确，请检查时间或必填项");
    }

    /**
     * 查询参数类型不匹配（例如 date=bad-date、id=abc）。
     *
     * <p>不加这条会落到兜底的 Exception 分支，用户看到的是「系统异常」，而实际是参数写错了。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.info("参数类型不匹配: name={} value={}", e.getName(), e.getValue());
        String required = "";
        if (e.getRequiredType() != null) {
            required = e.getRequiredType().getSimpleName().toLowerCase();
        }
        if ("localdate".equals(required)) {
            return Result.error(400, "日期格式不正确，应为 yyyy-MM-dd（例如 2026-10-30）");
        }
        if ("long".equals(required) || "integer".equals(required)) {
            return Result.error(400, "参数 " + e.getName() + " 必须是数字");
        }
        return Result.error(400, "参数 " + e.getName() + " 格式不正确");
    }

    /**
     * 缺少必填查询参数：同样属于 400，而非 500
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return Result.error(400, "缺少必要参数：" + e.getParameterName());
    }

    /**
     * @Validated 在方法参数上的校验失败（如 @RequestParam @Min）
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        return Result.error(400, msg.isEmpty() ? "参数校验失败" : msg);
    }

    /**
     * 上传文件超过 multipart 上限：给出可读提示，而不是笼统的「系统异常」
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        log.info("上传文件超过大小限制: {}", e.getMessage());
        return Result.error(400, "图片过大，请选择 2MB 以内的图片");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.error(500, "系统异常，请稍后重试");
    }
}
