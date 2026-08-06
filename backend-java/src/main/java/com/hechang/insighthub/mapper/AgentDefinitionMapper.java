package com.hechang.insighthub.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import com.hechang.insighthub.model.entity.AgentDefinition;
import com.mybatisflex.core.BaseMapper;

/**
 * Agent 定义 Mapper。
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinition> {

    /** 按工作空间列出 Agent */
    @Select("""
            SELECT id AS id,
                   workspace_id AS workspaceId,
                   name AS name,
                   agent_type AS agentType,
                   runtime AS runtime,
                   model_config_id AS modelConfigId,
                   prompt_version AS promptVersion,
                   system_prompt AS systemPrompt,
                   tool_permissions AS toolPermissions,
                   enabled AS enabled,
                   version AS version,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM agent_definition
            WHERE workspace_id = #{workspaceId}
            ORDER BY agent_type, name
            """)
    List<AgentDefinition> listByWorkspace(@Param("workspaceId") String workspaceId);

    /** 按 ID + 工作空间查询（强制租户隔离） */
    @Select("""
            SELECT id AS id,
                   workspace_id AS workspaceId,
                   name AS name,
                   agent_type AS agentType,
                   runtime AS runtime,
                   model_config_id AS modelConfigId,
                   prompt_version AS promptVersion,
                   system_prompt AS systemPrompt,
                   tool_permissions AS toolPermissions,
                   enabled AS enabled,
                   version AS version,
                   created_at AS createdAt,
                   updated_at AS updatedAt
            FROM agent_definition
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    AgentDefinition findByIdAndWorkspace(@Param("id") String id, @Param("workspaceId") String workspaceId);

    /** 统计工作空间内指定类型 Agent 数量 */
    @Select("""
            SELECT COUNT(*) FROM agent_definition
            WHERE workspace_id = #{workspaceId} AND agent_type = #{agentType}
            """)
    long countByWorkspaceAndType(@Param("workspaceId") String workspaceId, @Param("agentType") String agentType);

    /** 更新基础信息并递增 version */
    @Update("""
            UPDATE agent_definition
            SET name = #{name},
                system_prompt = #{systemPrompt},
                prompt_version = #{promptVersion},
                version = version + 1,
                updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateBasic(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("name") String name,
            @Param("systemPrompt") String systemPrompt,
            @Param("promptVersion") String promptVersion);

    /** 更新启用状态 */
    @Update("""
            UPDATE agent_definition
            SET enabled = #{enabled}, updated_at = NOW()
            WHERE id = #{id} AND workspace_id = #{workspaceId}
            """)
    int updateEnabled(
            @Param("id") String id,
            @Param("workspaceId") String workspaceId,
            @Param("enabled") int enabled);
}
