package com.dating.user.config;

import com.dating.user.constant.RedisKey;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 把 RedisKey 前缀从配置注进来.
 *
 * <p>Nacos / yml 中 {@code app.cache.key-prefix} 默认 putao:user,环境隔离
 * (dev / prod) 由独立 Nacos namespace 完成,前缀不带 dev/prod(CLAUDE.md 红线 14).
 */
@Slf4j
@Configuration
public class RedisKeyPrefixConfig {

    @Value("${app.cache.key-prefix:putao:user}")
    private String keyPrefix;

    @PostConstruct
    public void init() {
        RedisKey.setPrefix(keyPrefix);
        log.info("Redis key prefix initialized: {}", keyPrefix);
    }
}
