package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.SysRefreshToken;
import com.mybatisflex.core.BaseMapper;

/** 刷新令牌 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
@Mapper
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {
}
