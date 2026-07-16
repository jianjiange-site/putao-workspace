package com.dating.gateway;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Mobile Gateway Application.
 * 
 * <p>BFF layer that translates REST APIs to gRPC calls for downstream microservices.
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.dating.gateway.mapper")
public class MobileGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(MobileGatewayApplication.class, args);
    }
}
