package com.dating.payment.executor;

import com.dating.payment.constant.PaymentErrorCode;
import com.dating.payment.exception.PaymentBizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PayPal REST API 执行器.
 *
 * <p>封装所有 PayPal REST 交互，与业务逻辑解耦.
 * 支持可降级：缺少凭据时不崩服务，调用时才报错.
 */
@Slf4j
@Component
public class PaypalExecutor {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${paypal.client-id:}")
    private String clientId;

    @Value("${paypal.client-secret:}")
    private String clientSecret;

    @Value("${paypal.environment:sandbox}")
    private String environment;

    @Value("${paypal.webhook-id:}")
    private String webhookId;

    /** 缓存 access token */
    private String cachedAccessToken;
    private long tokenExpiresAt;
    private final AtomicLong tokenRefreshCount = new AtomicLong(0);

    public PaypalExecutor(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 检查 PayPal 是否已配置.
     */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    private String getBaseUrl() {
        return "live".equalsIgnoreCase(environment)
                ? "https://api-m.paypal.com"
                : "https://api-m.sandbox.paypal.com";
    }

    /**
     * 获取 access token（带缓存）.
     */
    private synchronized String getAccessToken() {
        if (cachedAccessToken != null && System.currentTimeMillis() < tokenExpiresAt - 60000) {
            return cachedAccessToken;
        }

        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Basic " + credentials);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        try {
            String tokenUrl = getBaseUrl() + "/v1/oauth2/token";
            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            var response = restTemplate.postForEntity(tokenUrl, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            cachedAccessToken = root.get("access_token").asText();
            int expiresIn = root.get("expires_in").asInt();
            tokenExpiresAt = System.currentTimeMillis() + (expiresIn - 120) * 1000L;
            tokenRefreshCount.incrementAndGet();

            log.info("PayPal access token refreshed, count={}", tokenRefreshCount.get());
            return cachedAccessToken;
        } catch (Exception e) {
            log.error("Failed to get PayPal access token", e);
            throw new PaymentBizException(500, "PayPal authentication failed");
        }
    }

    /**
     * 创建 PayPal 订单.
     *
     * @param orderId    内部订单号（custom_id）
     * @param amount     金额（元）
     * @param productName 商品名称
     * @param returnUrl  跳转 URL
     * @return 外部订单号 + approval link
     */
    public PayPalOrderResult createOrder(String orderId, BigDecimal amount,
                                        String productName, String returnUrl) {
        // 检查 PayPal 是否已配置
        if (!isConfigured()) {
            throw new IllegalStateException("PayPal not configured");
        }

        // 获取 access token
        String accessToken = getAccessToken();
        String url = getBaseUrl() + "/v2/checkout/orders";

        String requestBody;
        try {
            requestBody = """
                {
                  "intent": "CAPTURE",
                  "purchase_units": [{
                    "reference_id": "%s",
                    "description": "%s",
                    "amount": {
                      "currency_code": "USD",
                      "value": "%s"
                    }
                  }],
                  "application_context": {
                    "return_url": "%s",
                    "cancel_url": "%s",
                    "brand_name": "Dating App",
                    "landing_page": "BILLING",
                    "user_action": "PAY_NOW"
                  }
                }
                """.formatted(
                    orderId,
                    // 转义 JSON 字符串
                    escapeJson(productName),
                    // 金额
                    amount.setScale(2).toPlainString(),
                    // 跳转 URL
                    escapeJson(returnUrl != null ? returnUrl : ""),
                    escapeJson(returnUrl != null ? returnUrl : "")
            );
        } catch (Exception e) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CREATE_ORDER_FAILED, "Invalid order params");
        }

        try {
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 设置请求体
            HttpEntity<String> request = new HttpEntity<>(requestBody, headers);
            // 发送请求
            var response = restTemplate.postForEntity(url, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            // 获取状态
            String status = root.get("status").asText();

            // 如果状态不是 CREATED 或 PENDING，则抛出异常
            if (!"CREATED".equals(status) && !"PENDING".equals(status)) {
                log.warn("PayPal createOrder unexpected status: {}", status);
            }
            // 获取外部订单 ID
            String extOrderId = root.get("id").asText();

            // 找到 approval URL
            String approvalUrl = "";
            JsonNode links = root.get("links");
            if (links != null) {
                for (JsonNode link : links) {
                    if ("approve".equals(link.get("rel").asText())) {
                        approvalUrl = link.get("href").asText();
                        break;
                    }
                }
            }

            log.info("PayPal order created: orderId={}, extOrderId={}", orderId, extOrderId);
            return new PayPalOrderResult(extOrderId, approvalUrl);

        } catch (RestClientException e) {
            log.error("PayPal createOrder failed: orderId={}", orderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CREATE_ORDER_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("PayPal createOrder parse failed: orderId={}", orderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CREATE_ORDER_FAILED, e.getMessage());
        }
    }

    /**
     * 捕获（capture）PayPal 订单.
     *
     * @param extOrderId PayPal order id
     * @return 是否捕获成功（ORDER_ALREADY_CAPTURED 也返回 true）
     */
    public boolean captureOrder(String extOrderId) {
        if (!isConfigured()) {
            throw new IllegalStateException("PayPal not configured");
        }

        String accessToken = getAccessToken();
        String url = getBaseUrl() + "/v2/checkout/orders/" + extOrderId + "/capture";

        try {
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);

            // 设置请求体
            HttpEntity<String> request = new HttpEntity<>("{}", headers);
            // 发送请求
            var response = restTemplate.postForEntity(url, request, String.class);
            // 获取状态
            // 解析响应体
            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.get("status").asText();

            if ("COMPLETED".equals(status)) {
                log.info("PayPal capture success: extOrderId={}", extOrderId);
                return true;
            }

            // 幂等处理：订单已被捕获
            if (hasCaptureAlreadyCompletedError(root)) {
                log.info("PayPal order already captured (idempotent): extOrderId={}", extOrderId);
                return true;
            }

            log.warn("PayPal capture unexpected status: extOrderId={}, status={}", extOrderId, status);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Capture failed: " + status);

        } catch (PaymentBizException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("PayPal capture failed: extOrderId={}", extOrderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("PayPal capture parse failed: extOrderId={}", extOrderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED, e.getMessage());
        }
    }

    /**
     * 验证 PayPal webhook 签名.
     *
     * @param payload   原始 payload
     * @param headers   请求头
     * @return 验证通过返回 true
     */
    public boolean verifyWebhookSignature(String payload, String headers) {
        if (webhookId == null || webhookId.isBlank()) {
            log.warn("PayPal webhook-id not configured, skip verification");
            return true;
        }

        if (!isConfigured()) {
            return false;
        }

        String accessToken = getAccessToken();
        String url = getBaseUrl() + "/v1/notifications/verify-webhook-signature";

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Authorization", "Bearer " + accessToken);
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> request = new HttpEntity<>(payload, httpHeaders);
            var response = restTemplate.postForEntity(url, request, String.class);

            JsonNode root = objectMapper.readTree(response.getBody());
            String status = root.get("verification_status").asText();

            if ("SUCCESS".equals(status)) {
                return true;
            }

            log.warn("PayPal webhook signature verification failed: status={}", status);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_VERIFY_SIGNATURE_FAILED);

        } catch (PaymentBizException e) {
            throw e;
        } catch (Exception e) {
            log.error("PayPal webhook verification failed", e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_VERIFY_SIGNATURE_FAILED, e.getMessage());
        }
    }

    private boolean hasCaptureAlreadyCompletedError(JsonNode root) {
        try {
            JsonNode details = root.get("details");
            if (details != null && details.isArray()) {
                for (JsonNode d : details) {
                    if ("ORDER_ALREADY_CAPTURED".equals(d.get("issue").asText())) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
