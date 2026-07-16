package com.dating.gateway.vo;

public class CommentVO {
    private Long commentId;
    private Long postId;
    private Long userId;
    private Long rootId;
    private Long parentId;
    private Long replyToUserId;
    private String content;
    private Long createdAtSeconds;

    public Long getCommentId() { return commentId; }
    public void setCommentId(Long v) { this.commentId = v; }
    public Long getPostId() { return postId; }
    public void setPostId(Long v) { this.postId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public Long getRootId() { return rootId; }
    public void setRootId(Long v) { this.rootId = v; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long v) { this.parentId = v; }
    public Long getReplyToUserId() { return replyToUserId; }
    public void setReplyToUserId(Long v) { this.replyToUserId = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public Long getCreatedAtSeconds() { return createdAtSeconds; }
    public void setCreatedAtSeconds(Long v) { this.createdAtSeconds = v; }
}
