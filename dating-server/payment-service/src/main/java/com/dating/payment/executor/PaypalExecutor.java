package com.dating.payment.executor;

import com.dating.payment.constant.PaymentErrorCode;
import com.dating.payment.exception.PaymentBizException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
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

    @Value("${paypal.merchant-id:}")
    private String merchantId;

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
            ObjectNode root = objectMapper.createObjectNode();
            root.put("intent", "CAPTURE");
            ArrayNode purchaseUnits = root.putArray("purchase_units");
            ObjectNode purchaseUnit = purchaseUnits.addObject();
            purchaseUnit.put("reference_id", orderId);
            purchaseUnit.put("custom_id", orderId);
            purchaseUnit.put("invoice_id", orderId);
            purchaseUnit.put("description", productName);
            ObjectNode amountNode = purchaseUnit.putObject("amount");
            amountNode.put("currency_code", "USD");
            amountNode.put("value", amount.setScale(2).toPlainString());
            ObjectNode applicationContext = root.putObject("application_context");
            applicationContext.put("return_url", returnUrl != null ? returnUrl : "");
            applicationContext.put("cancel_url", returnUrl != null ? returnUrl : "");
            applicationContext.put("brand_name", "Dating App");
            applicationContext.put("landing_page", "BILLING");
            applicationContext.put("user_action", "PAY_NOW");
            requestBody = objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CREATE_ORDER_FAILED, "Invalid order params");
        }

        try {
            // 设置请求头
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("PayPal-Request-Id", orderId);

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
     * @return verified capture result
     */
    public PayPalCaptureResult captureOrder(String extOrderId) {
        if (!isConfigured()) {
            throw new IllegalStateException("PayPal not configured");
        }
        if (extOrderId == null || extOrderId.isBlank()) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Missing PayPal order id");
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
                return parseCaptureResult(root, extOrderId);
            }

            log.warn("PayPal capture unexpected status: extOrderId={}, status={}", extOrderId, status);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Capture failed: " + status);

        } catch (PaymentBizException e) {
            throw e;
        } catch (HttpStatusCodeException e) {
            if (hasCaptureAlreadyCompletedError(readJson(e.getResponseBodyAsString()))) {
                log.info("PayPal order already captured (idempotent): extOrderId={}", extOrderId);
                return getOrderDetails(extOrderId);
            }
            log.error("PayPal capture failed: extOrderId={}, status={}", extOrderId, e.getStatusCode());
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "PayPal capture request failed");
        } catch (RestClientException e) {
            log.error("PayPal capture failed: extOrderId={}", extOrderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "PayPal capture request failed");
        } catch (Exception e) {
            log.error("PayPal capture parse failed: extOrderId={}", extOrderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED, e.getMessage());
        }
    }

    /**
     * 验证 PayPal webhook 签名.
     *
     * @param payload   原始 payload
     * @return 验证通过返回 true
     */
    public boolean verifyWebhookSignature(String payload,
                                          String transmissionSig,
                                          String transmissionId,
                                          String transmissionTime,
                                          String certUrl,
                                          String authAlgo) {
        if (webhookId == null || webhookId.isBlank()) {
            log.error("PayPal webhook-id not configured; refusing webhook");
            return false;
        }

        if (!isConfigured()) {
            return false;
        }
        if (isBlank(transmissionSig) || isBlank(transmissionId) || isBlank(transmissionTime)
                || isBlank(certUrl) || isBlank(authAlgo)) {
            log.warn("PayPal webhook signature headers are incomplete");
            return false;
        }

        String accessToken = getAccessToken();
        String url = getBaseUrl() + "/v1/notifications/verify-webhook-signature";

        try {
            HttpHeaders httpHeaders = new HttpHeaders();
            httpHeaders.set("Authorization", "Bearer " + accessToken);
            httpHeaders.setContentType(MediaType.APPLICATION_JSON);

            ObjectNode body = objectMapper.createObjectNode();
            body.put("transmission_id", transmissionId);
            body.put("transmission_time", transmissionTime);
            body.put("cert_url", certUrl);
            body.put("auth_algo", authAlgo);
            body.put("transmission_sig", transmissionSig);
            body.put("webhook_id", webhookId);
            body.set("webhook_event", objectMapper.readTree(payload));
            HttpEntity<String> request =
                    new HttpEntity<>(objectMapper.writeValueAsString(body), httpHeaders);
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

    private PayPalCaptureResult getOrderDetails(String extOrderId) {
        String accessToken = getAccessToken();
        String url = getBaseUrl() + "/v2/checkout/orders/" + extOrderId;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + accessToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);
            var response = restTemplate.exchange(
                    url, org.springframework.http.HttpMethod.GET, request, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());
            if (!"COMPLETED".equals(root.path("status").asText())) {
                throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                        "PayPal order is not completed");
            }
            return parseCaptureResult(root, extOrderId);
        } catch (PaymentBizException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to query PayPal order: extOrderId={}", extOrderId, e);
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "Unable to verify captured PayPal order");
        }
    }

    private PayPalCaptureResult parseCaptureResult(JsonNode root, String expectedExtOrderId) {
        String responseOrderId = root.path("id").asText();
        if (!expectedExtOrderId.equals(responseOrderId)) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "PayPal order id mismatch");
        }

        JsonNode purchaseUnit = root.path("purchase_units").path(0);
        String merchantOrderId = purchaseUnit.path("custom_id").asText(
                purchaseUnit.path("reference_id").asText(""));
        JsonNode capturedAmount =
                purchaseUnit.path("payments").path("captures").path(0).path("amount");
        if (capturedAmount.isMissingNode()) {
            capturedAmount = purchaseUnit.path("amount");
        }
        String value = capturedAmount.path("value").asText();
        String currency = capturedAmount.path("currency_code").asText();
        String responseMerchantId =
                purchaseUnit.path("payee").path("merchant_id").asText("");
        if (value.isBlank() || currency.isBlank()) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "PayPal capture amount is missing");
        }
        if (!merchantId.isBlank() && !merchantId.equals(responseMerchantId)) {
            throw new PaymentBizException(PaymentErrorCode.PAYPAL_CAPTURE_FAILED,
                    "PayPal merchant id mismatch");
        }
        return new PayPalCaptureResult(
                responseOrderId,
                merchantOrderId,
                new BigDecimal(value),
                currency,
                responseMerchantId);
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
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
