package com.dating.gateway.vo;

import java.util.List;

/** User Profile VO. */
public class UserProfileVO {
    private Long userId;
    private String nickname;
    private String avatar;
    private Integer age;
    private String bio;

    public static UserProfileVOBuilder builder() {
        return new UserProfileVOBuilder();
    }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }

    public static class UserProfileVOBuilder {
        private Long userId;
        private String nickname;
        private String avatar;
        private Integer age;
        private String bio;

        public UserProfileVOBuilder userId(Long userId) { this.userId = userId; return this; }
        public UserProfileVOBuilder nickname(String nickname) { this.nickname = nickname; return this; }
        public UserProfileVOBuilder avatar(String avatar) { this.avatar = avatar; return this; }
        public UserProfileVOBuilder age(Integer age) { this.age = age; return this; }
        public UserProfileVOBuilder bio(String bio) { this.bio = bio; return this; }
        public UserProfileVO build() {
            UserProfileVO vo = new UserProfileVO();
            vo.setUserId(userId);
            vo.setNickname(nickname);
            vo.setAvatar(avatar);
            vo.setAge(age);
            vo.setBio(bio);
            return vo;
        }
    }
}
