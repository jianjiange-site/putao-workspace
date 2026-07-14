package com.dating.example;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Example Service Application Entry Point.
 *
 * <p>This service serves as a template/skeleton for creating new services.
 * Copy this service and modify the package name to create a new microservice.
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.dating.example.mapper")
public class ExampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }
}
