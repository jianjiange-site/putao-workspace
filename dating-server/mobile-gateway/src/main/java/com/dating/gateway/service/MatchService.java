package com.dating.gateway.service;

import com.dating.gateway.dto.SwipeReq;
import com.dating.gateway.dto.SuperHiReq;
import com.dating.gateway.vo.MatchCardVO;

import java.util.List;

public interface MatchService {
    boolean swipe(Long userId, SwipeReq req);
    boolean superHi(Long userId, SuperHiReq req);
    List<MatchCardVO> getMatches(Long userId);
    List<MatchCardVO> getSwipeHistory(Long userId, int pageSize, long cursor);
}
