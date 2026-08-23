package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.SysRefreshToken;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/** 刷新令牌 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {
    default SysRefreshToken findByTokenHash(String tokenHash) {
        return selectOneByQuery(QueryWrapper.create().eq(SysRefreshToken::getTokenHash, tokenHash));
    }
}
