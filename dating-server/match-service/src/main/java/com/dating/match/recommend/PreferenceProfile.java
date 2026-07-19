package com.dating.match.recommend;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 用户偏好画像.
 *
 * <p>D1 偏好建模(4.2.1)产物.样本不足(< 10)时所有字段为 null,由上层回退到 D0 偏好.
 */
@Data
@Builder
public class PreferenceProfile implements Serializable {

    /** 右划 target 平均年龄 */
    private Double ageMean;

    /** 右划 target 年龄标准差 */
    private Double ageStd;

    /** 右划 target 平均颜值分 */
    private Double beautyMean;

    /** 右划 target 颜值分标准差 */
    private Double beautyStd;

    /** 人种分布: race → 占比 (和为 1.0) */
    @Builder.Default
    private Map<String, Double> raceDist = new HashMap<>();

    /** 30 天右划 DH / (DH + BH) */
    private Double dhBhRatio;

    /** 样本数(右划数量) */
    private int sampleCount;

    public boolean isValid() {
        return sampleCount > 0 && ageMean != null && ageStd != null && beautyMean != null && beautyStd != null;
    }
}
