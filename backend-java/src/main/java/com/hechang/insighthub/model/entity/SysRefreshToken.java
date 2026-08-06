package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * JWT 刷新令牌实体，对应表 sys_refresh_token。
 */
@Data
@Table("sys_refresh_token")
public class SysRefreshToken {

    /** 令牌记录主键 */
    @Id(keyType = KeyType.None)
    private String id;

    @Column("user_id")
    private String userId;

    @Column("token_hash")
    private String tokenHash;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    /** 是否已吊销：0=否 1=是 */
    private Integer revoked;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;
}
