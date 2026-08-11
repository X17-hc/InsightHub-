package com.hechang.insighthub.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.hechang.insighthub.model.entity.SysUser;
import com.mybatisflex.core.BaseMapper;

/** 系统用户 Mapper：通用 CRUD 由 MyBatis-Flex BaseMapper 提供。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
