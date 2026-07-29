package com.dating.post.config;

import com.dating.post.constant.RedisKey;
import com.dating.post.manager.PostDetailCacheManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 跨实例帖子详情 L1 缓存失效通知.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PostCacheInvalidationConfig {

    private final PostDetailCacheManager postDetailCacheManager;

    @Bean
    public RedisMessageListenerContainer postCacheInvalidationContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                postDetailCacheManager.evictLocal(
                        Long.parseLong(message.toString()));
            } catch (Exception e) {
                log.warn("Ignore invalid post cache eviction message: value={}", message);
            }
        }, new ChannelTopic(RedisKey.postDetailEvictChannel()));
        return container;
    }
}
