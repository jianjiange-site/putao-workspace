package com.dating.post.config;

import com.dating.post.manager.PostDetailCacheManager;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置.
 */
@Configuration
@EnableCaching
public class CaffeineConfig {

    /**
     * 用户性别缓存: 30秒 TTL,最大 10000 条.
     */
    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("userGender");
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .recordStats());
        return manager;
    }

    /**
     * 帖子公共详情 L1 缓存: 15秒 TTL,最大 50000 条.
     */
    @Bean
    public Cache<Long, PostDetailCacheManager.CacheValue> postDetailLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterWrite(15, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
