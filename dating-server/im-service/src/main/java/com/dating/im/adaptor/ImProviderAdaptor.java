package com.dating.im.adaptor;

import com.dating.im.model.ImEvent;

/**
 * IM Provider 适配器接口.
 *
 * <p>抽象层,支持未来切换 IM 引擎.
 */
public interface ImProviderAdaptor {

    /**
     * 是否支持该 provider.
     */
    boolean supports(String provider);

    /**
     * 解析原始回调为 IM 事件.
     */
    ImEvent parse(byte[] rawPayload);
}
