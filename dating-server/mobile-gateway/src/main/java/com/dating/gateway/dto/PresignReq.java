package com.dating.gateway.dto;

public class PresignReq {
    private String ext = "jpg";
    private Long expectedSizeBytes;

    public String getExt() { return ext; }
    public void setExt(String v) { this.ext = v; }
    public Long getExpectedSizeBytes() { return expectedSizeBytes; }
    public void setExpectedSizeBytes(Long v) { this.expectedSizeBytes = v; }
}
