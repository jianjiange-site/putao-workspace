package com.dating.match;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Match Service Application Entry Point.
 *
 * <p>提供约会 App 的核心匹配服务:D0/D1 卡片队列、划卡、配对、Super Hi、
 * Like/Visit 互动体系、DH 模拟计划.
 */
@SpringBootApplication
@EnableDiscoveryClient
@EnableScheduling
@EnableAsync
@MapperScan("com.dating.match.mapper")
public class MatchServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchServiceApplication.class, args);
    }
}
