package com.dating.gateway.vo;

import java.util.List;

public class MatchCardVO {
    private Long targetUserId;
    private String nickname;
    private Integer age;
    private List<String> photoKeys;
    private String bio;
    private Double distanceKm;

    public Long getTargetUserId() { return targetUserId; }
    public void setTargetUserId(Long targetUserId) { this.targetUserId = targetUserId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }
    public List<String> getPhotoKeys() { return photoKeys; }
    public void setPhotoKeys(List<String> photoKeys) { this.photoKeys = photoKeys; }
    public String getBio() { return bio; }
    public void setBio(String bio) { this.bio = bio; }
    public Double getDistanceKm() { return distanceKm; }
    public void setDistanceKm(Double distanceKm) { this.distanceKm = distanceKm; }
}
