package com.dating.user.service;

import com.dating.user.vo.BanStatusVO;

/**
 * 封禁域服务接口 — 现由 UserIdentityService.checkBan 承载.
 *
 * <p>保留独立接口便于未来接后台审核工作流 / 风控事件.
 */
public interface UserBanService {

    /**
     * 查询封禁状态(短缓存 + 运营级 Set + DB regulation_status).
     */
    BanStatusVO checkBan(Long userId);
}