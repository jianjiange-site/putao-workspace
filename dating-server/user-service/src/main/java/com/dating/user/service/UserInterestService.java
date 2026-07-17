package com.dating.user.service;

import com.dating.user.dto.InterestDTO;
import com.dating.user.vo.UserInterestVO;

import java.util.List;

/**
 * 兴趣标签域服务接口.
 */
public interface UserInterestService {

    /**
     * 全量替换 — 事务内 DELETE + 批量 INSERT.
     *
     * @param userId 调用方 userId(metadata x-user-id)
     * @param dtos   新的兴趣列表(图片 ≤ 9 / 文字 ≤ 50)
     * @return 实际写入数量
     */
    int replaceUserInterests(Long userId, List<InterestDTO> dtos);

    /**
     * 单用户读取全量兴趣(供 GetProfile 用).
     */
    List<UserInterestVO> listByUserId(Long userId);
}