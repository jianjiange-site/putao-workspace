package com.dating.gateway.service;

import com.dating.gateway.vo.CallTokenVO;
import com.dating.gateway.vo.ImTokenVO;

/** IM Service Interface. */
public interface ImService {
    ImTokenVO getImToken(Long userId);
    CallTokenVO getCallToken(Long userId, String peerId);
}
