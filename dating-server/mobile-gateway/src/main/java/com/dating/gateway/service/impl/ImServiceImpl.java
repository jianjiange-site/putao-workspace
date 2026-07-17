package com.dating.gateway.service.impl;

import com.dating.gateway.client.ImClient;
import com.dating.gateway.service.ImService;
import com.dating.gateway.vo.CallTokenVO;
import com.dating.gateway.vo.ImTokenVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ImServiceImpl implements ImService {

    private static final Logger log = LoggerFactory.getLogger(ImServiceImpl.class);

    private final ImClient imClient;

    public ImServiceImpl(ImClient imClient) {
        this.imClient = imClient;
    }

    @Override
    public ImTokenVO getImToken(Long userId) {
        try {
            var resp = imClient.getImToken(userId, "", "");
            ImTokenVO vo = new ImTokenVO();
            vo.setUserId(userId);
            vo.setImToken(resp.getImToken());
            return vo;
        } catch (Exception e) {
            log.error("Failed to get IM token", e);
            return new ImTokenVO();
        }
    }

    @Override
    public CallTokenVO getCallToken(Long userId, String peerId) {
        return new CallTokenVO();
    }
}
