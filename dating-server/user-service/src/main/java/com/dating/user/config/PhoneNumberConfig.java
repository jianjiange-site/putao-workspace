package com.dating.user.config;

import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * libphonenumber 工具单例.
 *
 * <p>{@link PhoneNumberUtil} 自身线程安全,全进程复用一份即可.
 * 反向地理编码需要本地化元数据,MVP 暂不引入,留接口后续接入.
 */
@Configuration
public class PhoneNumberConfig {

    @Bean
    public PhoneNumberUtil phoneNumberUtil() {
        return PhoneNumberUtil.getInstance();
    }
}
