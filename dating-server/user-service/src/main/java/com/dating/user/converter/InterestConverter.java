package com.dating.user.converter;

import com.dating.user.dto.InterestDTO;
import com.dating.user.entity.UserInterestEntity;
import com.dating.user.vo.UserInterestVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * Interest Entity ↔ VO 转换器(MapStruct).
 *
 * <p>故意用 MapStruct 不去碰 proto builder —— proto 侧由 UserGrpcService
 * 手写,避免引入 MapStruct protobuf 扩展(踩设计文档红线 6).
 */
@Mapper
public interface InterestConverter {

    InterestConverter INSTANCE = Mappers.getMapper(InterestConverter.class);

    /** Entity → VO */
    UserInterestVO toVO(UserInterestEntity entity);

    /** DTO → Entity(ReplaceUserInterests 用) */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    UserInterestEntity toEntity(InterestDTO dto);
}