package com.dating.match.constant;

/**
 * match_outbox action 枚举.
 */
public final class OutboxActionConst {

    /** im-service.EnsureConversation */
    public static final String ENSURE_CONVERSATION = "ENSURE_CONVERSATION";
    /** im-service.SendSystemMessage (双方) */
    public static final String SYSTEM_MSG = "SYSTEM_MSG";
    /** im-service.TriggerDhOpening (DH 端) */
    public static final String DH_OPENING = "DH_OPENING";

    private OutboxActionConst() {
    }
}
