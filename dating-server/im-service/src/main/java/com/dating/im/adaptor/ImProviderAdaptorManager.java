package com.dating.im.adaptor;

import com.dating.im.model.ImEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * IM Provider 适配器管理器.
 *
 * <p>管理多个 IM 引擎适配器,按 provider 分发.
 */
@Slf4j
@Component
public class ImProviderAdaptorManager {

    private final List<ImProviderAdaptor> adaptors;

    public ImProviderAdaptorManager(List<ImProviderAdaptor> adaptors) {
        this.adaptors = adaptors;
    }

    /**
     * 根据 provider 选择适配器并解析事件.
     *
     * @param provider   IM 引擎标识
     * @param rawPayload 原始回调数据
     * @return 归一化事件,无法解析返回 UnknownEvent
     */
    public ImEvent parse(String provider, byte[] rawPayload) {
        for (ImProviderAdaptor adaptor : adaptors) {
            if (adaptor.supports(provider)) {
                try {
                    return adaptor.parse(rawPayload);
                } catch (Exception e) {
                    log.error("Adaptor failed: provider={}", provider, e);
                }
            }
        }

        log.warn("No adaptor found for provider: {}", provider);
        return ImEvent.UnknownEvent.builder()
                .type("unsupported")
                .provider(provider)
                .build();
    }
}
