package com.dating.im.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 联系方式检测服务.
 *
 * <p>检测消息中是否包含站外联系方式.
 */
@Slf4j
@Service
public class ContactInfoDetector {

    private final ObjectMapper objectMapper;

    public ContactInfoDetector(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // 联系方式检测正则
    private static final String INSTAGRAM = "instagram";
    private static final String FACEBOOK = "facebook";
    private static final String WHATSAPP = "whatsapp";
    private static final String TELEGRAM = "telegram";
    private static final String US_PHONE = "us_phone";

    /**
     * 检测联系方式.
     *
     * @param content 消息内容
     * @return 检测到的类型名称, null 表示未检测到
     */
    public String detect(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        String lower = content.toLowerCase();

        // Instagram
        if (containsInstagram(lower)) {
            return INSTAGRAM;
        }

        // Facebook
        if (containsFacebook(lower)) {
            return FACEBOOK;
        }

        // WhatsApp
        if (containsWhatsapp(lower)) {
            return WHATSAPP;
        }

        // Telegram
        if (containsTelegram(lower)) {
            return TELEGRAM;
        }

        // 美国电话号码
        if (containsUsPhone(content)) {
            return US_PHONE;
        }

        return null;
    }

    private boolean containsInstagram(String lower) {
        return lower.contains("instagram.com/") ||
               lower.matches(".*ig:\\s*@?\\w+.*") ||
               lower.matches(".*insta\\s*@?\\w+.*");
    }

    private boolean containsFacebook(String lower) {
        return lower.contains("facebook.com/") ||
               lower.matches(".*fb:\\s*@?\\w+.*") ||
               lower.matches(".*facebook\\s*@?\\w+.*");
    }

    private boolean containsWhatsapp(String lower) {
        return lower.contains("wa.me/") ||
               lower.contains("whatsapp");
    }

    private boolean containsTelegram(String lower) {
        return lower.contains("t.me/") ||
               lower.matches(".*tg:\\s*@?\\w+.*") ||
               lower.matches(".*telegram\\s*@?\\w+.*");
    }

    private boolean containsUsPhone(String content) {
        // 美国号码格式: (123) 456-7890, +1 123-456-7890, 123-456-7890
        // 使用前后边界守卫防误伤
        return content.matches(".*\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{4}.*") ||
               content.matches(".*\\+1[\\s-]?\\(?\\d{3}\\)?[\\s-]?\\d{3}[\\s-]?\\d{4}.*");
    }
}
