package com.dating.gateway.service.impl;

import com.dating.gateway.client.ImClient;
import com.dating.gateway.exception.GatewayException;
import com.dating.gateway.service.ImService;
import com.dating.gateway.vo.CallTokenVO;
import com.dating.gateway.vo.ImTokenVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImServiceImpl implements ImService {

    private final ImClient imClient;

    @Override
    public ImTokenVO getImToken(Long userId) {
        try {
            var resp = imClient.getImToken(userId);
            ImTokenVO vo = new ImTokenVO();
            vo.setUserId(userId);
            vo.setImToken(resp.getToken());
            return vo;
        } catch (Exception e) {
            log.error("Failed to get IM token", e);
            throw new GatewayException(10601, "Failed to get IM token");
        }
    }

    @Override
    public CallTokenVO getCallToken(Long userId, Long roomId, boolean isBroadcaster) {
        try {
            var resp = imClient.getCallToken(userId, roomId, isBroadcaster);
            CallTokenVO vo = new CallTokenVO();
            vo.setToken(resp.getToken());
            return vo;
        } catch (Exception e) {
            log.error("Failed to get call token", e);
            throw new GatewayException(10602, "Failed to get call token");
        }
    }
}
