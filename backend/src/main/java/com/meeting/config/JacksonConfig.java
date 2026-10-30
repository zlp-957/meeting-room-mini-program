package com.meeting.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Jackson 时间配置
 *
 * <p>P0-2：Spring 的 {@code spring.jackson.date-format} 对 JSR-310（LocalDateTime）
 * 不生效，默认只认 ISO 的 {@code T} 分隔格式。小程序提交 {@code 2026-10-27 10:00:00}
 * 时反序列化直接失败并返回 500。</p>
 *
 * <p>这里统一：序列化输出 {@code yyyy-MM-dd HH:mm:ss}；反序列化同时兼容
 * {@code 2026-10-27 10:00:00} 与 {@code 2026-10-27T10:00:00} 两种写法。</p>
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jsr310Customizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("meeting-jsr310");
            module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
            module.addSerializer(LocalDate.class, new LocalDateSerializer(DATE));
            module.addSerializer(LocalTime.class, new LocalTimeSerializer(TIME));
            module.addDeserializer(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override
                public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    String text = p.getValueAsString();
                    if (text == null || text.trim().isEmpty()) {
                        return null;
                    }
                    try {
                        return parseDateTime(text.trim());
                    } catch (DateTimeParseException e) {
                        throw ctxt.weirdStringException(text, LocalDateTime.class, "时间格式应为 yyyy-MM-dd HH:mm:ss");
                    }
                }
            });
            module.addDeserializer(LocalDate.class, new JsonDeserializer<LocalDate>() {
                @Override
                public LocalDate deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    String text = p.getValueAsString();
                    if (text == null || text.trim().isEmpty()) {
                        return null;
                    }
                    try {
                        return LocalDate.parse(text.trim().replace('/', '-'), DATE);
                    } catch (DateTimeParseException e) {
                        throw ctxt.weirdStringException(text, LocalDate.class, "日期格式应为 yyyy-MM-dd");
                    }
                }
            });
            module.addDeserializer(LocalTime.class, new JsonDeserializer<LocalTime>() {
                @Override
                public LocalTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                    String text = p.getValueAsString();
                    if (text == null || text.trim().isEmpty()) {
                        return null;
                    }
                    try {
                        String t = text.trim();
                        // 兼容 HH:mm，补齐秒
                        if (t.length() == 5) {
                            t = t + ":00";
                        }
                        return LocalTime.parse(t, TIME);
                    } catch (DateTimeParseException e) {
                        throw ctxt.weirdStringException(text, LocalTime.class, "时间格式应为 HH:mm:ss");
                    }
                }
            });
            builder.modulesToInstall(module);
        };
    }

    /**
     * 兼容空格与 T 两种分隔符
     */
    static LocalDateTime parseDateTime(String text) {
        String t = text.replace('T', ' ').replace('/', '-').trim();
        int dot = t.indexOf('.');
        if (dot > 0) {
            t = t.substring(0, dot);
        }
        if (t.length() == 16) {
            t = t + ":00";
        }
        return LocalDateTime.parse(t, DATE_TIME);
    }
}
