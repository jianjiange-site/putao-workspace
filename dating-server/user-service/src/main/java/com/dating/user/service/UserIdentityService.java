package com.dating.user.service;

import com.dating.user.dto.ResolveOrCreateDTO;
import com.dating.user.vo.BanStatusVO;
import com.dating.user.vo.ResolveOrCreateVO;

/**
 * 用户身份解析服务接口 — 由 mobile-gateway 在登录链路调.
 */
public interface UserIdentityService {

    /**
     * 按手机号(E.164 已规范化)解析或创建.
     *
     * <p>注册解析锁粒度: {@code lock:user:register:phone:{phoneE164}:{appName}}.
     *
     * @param dto 入参,phoneE164 / appName 必填
     * @return userId + pending + newlyCreated
     */
    ResolveOrCreateVO resolveOrCreateByPhone(ResolveOrCreateDTO dto);

    /**
     * 按第三方平台账号解析或创建.
     *
     * <p>注册解析锁粒度: {@code lock:user:register:tp:{platform}:{thirdPartyUserId}:{appName}}.
     */
    ResolveOrCreateVO resolveOrCreateByThirdParty(ResolveOrCreateDTO dto);

    /**
     * 按设备 ID(快速登录)解析或创建.
     *
     * <p>注册解析锁粒度: {@code lock:user:register:device:{deviceId}:{platform}:{appName}}.
     */
    ResolveOrCreateVO resolveOrCreateByDevice(ResolveOrCreateDTO dto);

    /**
     * 检查封禁状态(短缓存 + Redis Set + DB regulation_status).
     */
    BanStatusVO checkBan(Long userId);
}
