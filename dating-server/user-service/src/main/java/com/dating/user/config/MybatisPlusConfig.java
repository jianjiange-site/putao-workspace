package com.dating.user.config;

/**
 * MyBatis-Plus 配置 — 当前 MVP 不需要分页,留空类占位.
 *
 * <p>项目其他红线在 Mapper 层遵守(单表 CRUD / 禁 JOIN / 禁手写 SQL 字符串拼接),
 * 由单元测试 / PR review 兜底.
 *
 * <p>后续需要分页时,在这里注册 {@code com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor}
 * 并加入分页 inner(注意 3.5.9 jar 包结构,分页入口在
 * {@code com.baomidou.mybatisplus.extension.plugins.pagination.*}).
 */
public final class MybatisPlusConfig {

    private MybatisPlusConfig() {
    }
}