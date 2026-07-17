package com.dating.im.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 事件模型.
 *
 * <p>归一化的事件类型,不暴露 OpenIM 字段.
 */
public sealed interface ImEvent permits ImEvent.MessageSentEvent, ImEvent.MessageBeforeSendEvent,
        ImEvent.UserOnlineEvent, ImEvent.UserOfflineEvent, ImEvent.UnknownEvent {

    /** 消息已发送事件 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class MessageSentEvent implements ImEvent {
        private String messageId;
        private Long fromUserId;
        private Long toUserId;
        private String content;
        private Integer msgType;
        private String conversationType;
        private Long timestamp;
        private String provider;
    }

    /** 消息发送前事件(before-send) */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class MessageBeforeSendEvent implements ImEvent {
        private String messageId;
        private Long fromUserId;
        private Long toUserId;
        private String content;
        private Integer msgType;
        private String conversationType;
        private Long timestamp;
    }

    /** 用户上线事件 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class UserOnlineEvent implements ImEvent {
        private Long userId;
        private Integer platform;
        private Long onlineAt;
    }

    /** 用户下线事件 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class UserOfflineEvent implements ImEvent {
        private Long userId;
        private Integer platform;
        private Long offlineAt;
    }

    /** 未知事件 */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    final class UnknownEvent implements ImEvent {
        private String type;
        private String provider;
    }
}
