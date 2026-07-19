package com.dating.payment;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Payment Service Application Entry Point.
 *
 * <p>提供支付、金币、订阅、提现等核心服务.
 */
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.dating.payment.mapper")
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
