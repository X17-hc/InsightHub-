package com.hechang.insighthub.mapper;

import com.hechang.insighthub.model.entity.SysUser;
import com.mybatisflex.core.BaseMapper;
import com.mybatisflex.core.query.QueryWrapper;

/** 系统用户 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
public interface SysUserMapper extends BaseMapper<SysUser> {
    default SysUser findByUsername(String username) {
        return selectOneByQuery(QueryWrapper.create().eq(SysUser::getUsername, username));
    }

    default boolean existsByUsername(String username) {
        return selectCountByQuery(QueryWrapper.create().eq(SysUser::getUsername, username)) > 0;
    }

    default boolean existsByEmail(String email) {
        return selectCountByQuery(QueryWrapper.create().eq(SysUser::getEmail, email)) > 0;
    }
}
