package com.dating.payment.controller;

import com.dating.payment.service.PaymentService;
import com.dating.payment.vo.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * PayPal Webhook Controller.
 */
@Slf4j
@RestController
@RequestMapping("/v1/payments/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    /**
     * PayPal Webhook 回调.
     *
     * @param payload   原始 body
     * @param eventType 事件类型
     * @param resource  资源对象
     */
    @PostMapping(value = "/paypal", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<?> handlePayPalWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-SIG", required = false) String sig,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
            @RequestHeader(value = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime,
            @RequestHeader(value = "PAYPAL-CERT-URL", required = false) String certUrl) {
        log.info("PayPal webhook received: payload={}", payload);

        try {
            JsonNode root = objectMapper.readTree(payload);
            String eventType = root.path("event_type").asText();
            JsonNode resource = root.path("resource");

            String orderId = resource.path("custom_id").asText(null);
            String extOrderId = resource.path("id").asText(null);

            log.info("PayPal webhook: eventType={}, orderId={}, extOrderId={}",
                    eventType, orderId, extOrderId);

            paymentService.handlePayPalWebhook(payload, eventType, orderId, extOrderId);

            return Result.ok("OK");
        } catch (Exception e) {
            log.error("PayPal webhook handle failed", e);
            return Result.fail(500, "Webhook processing failed");
        }
    }
}
