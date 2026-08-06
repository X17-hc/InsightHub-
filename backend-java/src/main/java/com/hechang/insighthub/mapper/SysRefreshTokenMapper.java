package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.SysRefreshToken;
import com.mybatisflex.core.BaseMapper;

/**
 * 刷新令牌 Mapper。
 */
@Mapper
public interface SysRefreshTokenMapper extends BaseMapper<SysRefreshToken> {

    /** 按令牌哈希查询（AS 别名保证注解 SQL 映射到驼峰字段） */
    @Select("""
            SELECT id AS id,
                   user_id AS userId,
                   token_hash AS tokenHash,
                   expires_at AS expiresAt,
                   revoked AS revoked,
                   created_at AS createdAt
            FROM sys_refresh_token
            WHERE token_hash = #{tokenHash}
            """)
    SysRefreshToken selectByTokenHash(@Param("tokenHash") String tokenHash);

    /** 按主键吊销令牌 */
    @Update("UPDATE sys_refresh_token SET revoked = 1 WHERE id = #{id}")
    int revokeById(@Param("id") String id);
}
