package com.hechang.insighthub.model.entity;

import java.time.LocalDateTime;

import com.mybatisflex.annotation.Column;
import com.mybatisflex.annotation.Id;
import com.mybatisflex.annotation.KeyType;
import com.mybatisflex.annotation.Table;

import lombok.Data;

/**
 * 系统用户实体，对应表 sys_user。
 */
@Data
@Table("sys_user")
public class SysUser {

    /** 用户业务主键 */
    @Id(keyType = KeyType.None)
    private String id;

    private String username;

    /** 密码哈希（禁止存明文） */
    @Column("password_hash")
    private String passwordHash;

    private String email;

    @Column("display_name")
    private String displayName;

    @Column("avatar_url")
    private String avatarUrl;

    /** 状态：1=启用 0=禁用 2=待激活 */
    private Integer status;

    @Column("last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(value = "created_at", onInsertValue = "now()")
    private LocalDateTime createdAt;

    @Column(value = "updated_at", onInsertValue = "now()", onUpdateValue = "now()")
    private LocalDateTime updatedAt;
}
