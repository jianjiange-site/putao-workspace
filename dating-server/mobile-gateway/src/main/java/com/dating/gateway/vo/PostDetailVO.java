package com.dating.gateway.vo;

import java.util.List;

/** Post Detail VO. */
public class PostDetailVO {
    private Long postId;
    private Long userId;
    private String content;
    private List<String> imageKeys;
    private int likeCount;
    private int commentCount;
    private boolean isLiked;
    private long createdAtSeconds;

    public static PostDetailVOBuilder builder() {
        return new PostDetailVOBuilder();
    }

    public Long getPostId() { return postId; }
    public void setPostId(Long postId) { this.postId = postId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public List<String> getImageKeys() { return imageKeys; }
    public void setImageKeys(List<String> imageKeys) { this.imageKeys = imageKeys; }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getCommentCount() { return commentCount; }
    public void setCommentCount(int commentCount) { this.commentCount = commentCount; }
    public boolean isLiked() { return isLiked; }
    public void setLiked(boolean isLiked) { this.isLiked = isLiked; }
    public long getCreatedAtSeconds() { return createdAtSeconds; }
    public void setCreatedAtSeconds(long createdAtSeconds) { this.createdAtSeconds = createdAtSeconds; }

    public static class PostDetailVOBuilder {
        private Long postId;
        private Long userId;
        private String content;
        private List<String> imageKeys;
        private int likeCount;
        private int commentCount;
        private boolean isLiked;
        private long createdAtSeconds;

        public PostDetailVOBuilder postId(Long postId) { this.postId = postId; return this; }
        public PostDetailVOBuilder userId(Long userId) { this.userId = userId; return this; }
        public PostDetailVOBuilder content(String content) { this.content = content; return this; }
        public PostDetailVOBuilder imageKeys(List<String> imageKeys) { this.imageKeys = imageKeys; return this; }
        public PostDetailVOBuilder likeCount(int likeCount) { this.likeCount = likeCount; return this; }
        public PostDetailVOBuilder commentCount(int commentCount) { this.commentCount = commentCount; return this; }
        public PostDetailVOBuilder isLiked(boolean isLiked) { this.isLiked = isLiked; return this; }
        public PostDetailVOBuilder createdAtSeconds(long createdAtSeconds) { this.createdAtSeconds = createdAtSeconds; return this; }
        public PostDetailVO build() {
            PostDetailVO vo = new PostDetailVO();
            vo.setPostId(postId);
            vo.setUserId(userId);
            vo.setContent(content);
            vo.setImageKeys(imageKeys);
            vo.setLikeCount(likeCount);
            vo.setCommentCount(commentCount);
            vo.setLiked(isLiked);
            vo.setCreatedAtSeconds(createdAtSeconds);
            return vo;
        }
    }
}
