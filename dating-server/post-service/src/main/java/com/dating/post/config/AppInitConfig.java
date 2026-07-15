package com.dating.post.config;

import com.dating.post.constant.RedisKey;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * 应用初始化配置.
 *
 * <p>在应用启动时初始化全局常量.
 */
@Slf4j
@Configuration
public class AppInitConfig {

    @Value("${app.cache.key-prefix:putao}")
    private String keyPrefix;

    @PostConstruct
    public void init() {
        // 初始化 Redis Key 前缀
        RedisKey.setPrefix(keyPrefix);
        log.info("App initialized, redis key prefix: {}", keyPrefix);
    }
}
