package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.SysRefreshToken;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 刷新令牌 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {
    default SysRefreshToken findByTokenHash(String tokenHash) {
        return selectOneByQuery(QueryWrapper.create().eq(SysRefreshToken::getTokenHash, tokenHash));
    }

    /**
     * 原子消费尚未撤销且未过期的刷新令牌。返回 1 表示本次请求取得轮换权；
     * 返回 0 表示令牌已被并发请求消费、已撤销或已过期。
     */
    @Update("""
            UPDATE sys_refresh_token
               SET revoked = 1
             WHERE id = #{id}
               AND revoked = 0
               AND expires_at > UTC_TIMESTAMP()
            """)
    int revokeIfActive(@Param("id") String id);
}
