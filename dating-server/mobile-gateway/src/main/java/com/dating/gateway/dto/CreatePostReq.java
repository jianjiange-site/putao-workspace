package com.dating.gateway.dto;

import java.util.List;

public class CreatePostReq {
    private String content;
    private List<String> imageKeys;

    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public List<String> getImageKeys() { return imageKeys; }
    public void setImageKeys(List<String> v) { this.imageKeys = v; }
}
