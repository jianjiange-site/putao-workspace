package com.dating.example.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dating.example.entity.ExampleEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Example Mapper.
 *
 * <p>MyBatis-Plus mapper for example table.
 * Each mapper operates on exactly one table.
 */
@Mapper
public interface ExampleMapper extends BaseMapper<ExampleEntity> {

}
