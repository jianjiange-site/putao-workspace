package com.dating.user.service.impl;

import com.dating.user.manager.UserBanManager;
import com.dating.user.manager.UserInfoManager;
import com.dating.user.proto.BanReason;
import com.dating.user.service.UserBanService;
import com.dating.user.vo.BanStatusVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * UserBan Service 实现.
 *
 * <p>逻辑与 UserIdentityServiceImpl.checkBan 一致,但通过独立 Service 暴露,
 * 便于未来直接调(运营后台、风控服务等不通过 identity 域的场景).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserBanServiceImpl implements UserBanService {

    private final UserInfoManager userInfoManager;
    private final UserBanManager userBanManager;

    @Override
    public BanStatusVO checkBan(Long userId) {
        if (userId == null) {
            return BanStatusVO.builder().banned(false).reason("NONE").build();
        }

        // 1. 短缓存
        BanReason cached = userBanManager.queryBanReason(userId);
        if (cached != null) {
            return toBanStatusVO(cached);
        }

        // 2. 运营级封禁
        if (userBanManager.isOperationalBanned(userId)) {
            userBanManager.cacheBanReason(userId, BanReason.BAN_REASON_OPERATIONAL);
            return BanStatusVO.builder()
                    .banned(true)
                    .reason(BanReason.BAN_REASON_OPERATIONAL.name())
                    .message("Account restricted")
                    .build();
        }

        // 3. DB regulation_status
        var info = userInfoManager.findByUserId(userId);
        BanReason reason = UserBanManager.reasonFromRegulationStatus(
                info != null ? info.getRegulationStatus() : null);
        userBanManager.cacheBanReason(userId, reason);
        return toBanStatusVO(reason);
    }

    private BanStatusVO toBanStatusVO(BanReason reason) {
        boolean banned = reason != BanReason.BAN_REASON_NONE;
        String msg = switch (reason) {
            case BAN_REASON_USER_BANNED -> "Account banned";
            case BAN_REASON_USER_SUSPENDED -> "Account suspended";
            case BAN_REASON_OPERATIONAL -> "Account restricted";
            default -> "";
        };
        return BanStatusVO.builder()
                .banned(banned)
                .reason(reason.name())
                .bannedAtMs(0L)
                .message(banned ? msg : "")
                .build();
    }
}