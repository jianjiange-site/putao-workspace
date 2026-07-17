package com.dating.gateway.dto;

public class OnboardingReq {
    private String nickname;
    private Integer gender;
    private String birthday;
    private Integer age;
    private Integer height;
    private String bio;
    private String occupation;
    private String education;
    private String location;
    private String defaultAvatarObjectKey;

    public String getNickname() { return nickname; }
    public void setNickname(String v) { this.nickname = v; }
    public Integer getGender() { return gender; }
    public void setGender(Integer v) { this.gender = v; }
    public String getBirthday() { return birthday; }
    public void setBirthday(String v) { this.birthday = v; }
    public Integer getAge() { return age; }
    public void setAge(Integer v) { this.age = v; }
    public Integer getHeight() { return height; }
    public void setHeight(Integer v) { this.height = v; }
    public String getBio() { return bio; }
    public void setBio(String v) { this.bio = v; }
    public String getOccupation() { return occupation; }
    public void setOccupation(String v) { this.occupation = v; }
    public String getEducation() { return education; }
    public void setEducation(String v) { this.education = v; }
    public String getLocation() { return location; }
    public void setLocation(String v) { this.location = v; }
    public String getDefaultAvatarObjectKey() { return defaultAvatarObjectKey; }
    public void setDefaultAvatarObjectKey(String v) { this.defaultAvatarObjectKey = v; }
}
