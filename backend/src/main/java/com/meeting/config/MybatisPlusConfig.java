package com.meeting.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 配置
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }

    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                this.setFieldValByName("createTime", now, metaObject);
                this.setFieldValByName("updateTime", now, metaObject);
            }

            /**
             * 更新时强制刷新 update_time。
             *
             * <p>不能用 {@code strictUpdateFill}：它的语义是「字段为 null 才填充」，
             * 而更新场景的实体通常是从库里查出来的（updateTime 已有旧值），
             * 于是 update_time 永远保持首次写入的时间 —— 改了头像/昵称，库里时间还是几个月前的。
             * 这里改成无条件覆盖。</p>
             */
            @Override
            public void updateFill(MetaObject metaObject) {
                this.setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
            }
        };
    }
}
