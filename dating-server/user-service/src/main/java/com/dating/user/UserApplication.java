package com.dating.user;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * User Service Application Entry Point.
 *
 * <p>用户身份解析 + 资料域服务. 对外只暴露 gRPC (:9090),HTTP (:8080) 仅承载
 * Actuator 健康检查端点. 调用方当前为 {@code mobile-gateway},未来会接
 * {@code relation-service / im-service} 等.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@MapperScan("com.dating.user.mapper")
public class UserApplication {

    public static void main(String[] args) {
        // 强制 JVM 时区为 UTC,所有时区相关逻辑统一在 UTC 下处理
        System.setProperty("user.timezone", "UTC");
        SpringApplication.run(UserApplication.class, args);
    }
}
