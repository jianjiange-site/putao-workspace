package com.dating.payment.controller;

import org.springframework.web.bind.annotation.*;

/**
 * 健康检查 Controller.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public String health() {
        return "OK";
    }
}
