package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.SysUser;
import com.mybatisflex.core.BaseMapper;

/**
 * 系统用户 Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 按用户名统计数量（判重） */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
    long countByUsername(@Param("username") String username);

    /** 按邮箱统计数量（判重） */
    @Select("SELECT COUNT(*) FROM sys_user WHERE email = #{email}")
    long countByEmail(@Param("email") String email);

    /** 按用户名查询用户（AS 别名保证注解 SQL 映射到驼峰字段） */
    @Select("""
            SELECT id AS id,
                   username AS username,
                   password_hash AS passwordHash,
                   email AS email,
                   display_name AS displayName,
                   avatar_url AS avatarUrl,
                   status AS status,
                   last_login_at AS lastLoginAt,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM sys_user
            WHERE username = #{username}
            """)
    SysUser selectByUsername(@Param("username") String username);

    /** 更新最近登录时间为当前时间 */
    @Update("UPDATE sys_user SET last_login_at = NOW() WHERE id = #{userId}")
    int touchLastLogin(@Param("userId") String userId);
}
