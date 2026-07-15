package com.dating.post.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;

/**
 * 启动早期探针:在 datasource / redis / redisson 任何 bean 初始化之前,
 * 打印 Spring 看到的"敏感字段"实际值,定位"为什么 Nacos 的 password 没生效".
 *
 * <p>挂在 spring.factories / spring.application.imports 里, 比 SpringApplication.run 更早.
 */
@Slf4j
public class EnvDiagnosticsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        // 此时 env 包含 application.yml + Nacos 拉下来的 PropertySource
        String[] keys = {
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host",
                "spring.data.redis.port",
                "spring.data.redis.password",
                "spring.data.redis.database",
                "redisson.single-server-config.address",
                "redisson.single-server-config.password",
                "redisson.single-server-config.database",
        };
        log.warn("[DBG-ENV] ============== ENV SNAPSHOT ==============");
        for (String key : keys) {
            String v = env.getProperty(key);
            String masked;
            if (v == null) {
                masked = "<null>";
            } else if (key.contains("password") || key.contains("secret")) {
                masked = "***(len=" + v.length() + ")";
            } else {
                masked = v;
            }
            log.warn("[DBG-ENV] {} = {}", key, masked);
        }
        log.warn("[DBG-ENV] active profiles = {}", Arrays.toString(env.getActiveProfiles()));
        log.warn("[DBG-ENV] property sources =");
        env.getPropertySources().forEach(ps -> log.warn("[DBG-ENV]   - {}", ps.getName()));
        log.warn("[DBG-ENV] ============================================");
    }
}
