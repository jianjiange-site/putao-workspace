package com.dating.user.converter;

import com.dating.user.entity.UserInfoEntity;
import com.dating.user.vo.AvatarVO;
import com.dating.user.vo.UserProfileVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

import java.util.List;

/**
 * UserInfo Entity → UserProfileVO 转换(MapStruct).
 *
 * <p>关键映射:
 * <ul>
 *   <li>DB 字段 {@code profession} → VO {@code occupation}(UI 字段名,设计文档 §5.2 表)</li>
 *   <li>{@code pending} Integer → Boolean</li>
 *   <li>嵌套 {@code avatar} / {@code interests} 由 service 层手动拼(避免 MapStruct 深嵌套踩坑)</li>
 * </ul>
 */
@Mapper
public interface UserProfileConverter {

    UserProfileConverter INSTANCE = Mappers.getMapper(UserProfileConverter.class);

    @Mappings({
            // VO 字段名沿用 profession(设计文档 §5.2 表内命名为 occupation,VO 一律 profession)
            @Mapping(target = "avatar", ignore = true),
            @Mapping(target = "interests", ignore = true),
            @Mapping(target = "pending", expression = "java(entity.getPending() != null && entity.getPending() == 1)"),
            // lastOpenAtMs 由 service 层在从 entity.lastOpenAt 计算得到,不在 mapper 这里处理
            @Mapping(target = "lastOpenAtMs", ignore = true)
    })
    UserProfileVO toVO(UserInfoEntity entity);

    /** 头像独立构建方法 */
    default AvatarVO buildAvatar(String originalKey, String minKey, String midKey) {
        if (originalKey == null && minKey == null && midKey == null) return null;
        return AvatarVO.builder()
                .originalKey(originalKey)
                .minKey(minKey)
                .midKey(midKey)
                .build();
    }
}
