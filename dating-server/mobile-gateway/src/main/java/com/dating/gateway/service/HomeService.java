package com.dating.gateway.service;

import com.dating.gateway.vo.HomeCardVO;

import java.util.List;

public interface HomeService {
    List<HomeCardVO> getHomeCards(Long userId, int pageSize);
}
