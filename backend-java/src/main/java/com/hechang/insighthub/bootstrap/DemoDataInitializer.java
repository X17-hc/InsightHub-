package com.hechang.insighthub.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.hechang.insighthub.mapper.AgentDefinitionMapper;
import com.hechang.insighthub.mapper.SysUserMapper;
import com.hechang.insighthub.mapper.WorkspaceMapper;
import com.hechang.insighthub.mapper.WorkspaceMemberMapper;
import com.hechang.insighthub.model.entity.AgentDefinition;
import com.mybatisflex.core.query.QueryWrapper;
import com.hechang.insighthub.model.entity.SysUser;
import com.hechang.insighthub.model.entity.Workspace;
import com.hechang.insighthub.model.entity.WorkspaceMember;
import com.hechang.insighthub.config.DemoProperties;

import lombok.RequiredArgsConstructor;

/**
 * 启动 seed：双用户 / 双工作空间 / 默认 Agent（可通过 insighthub.demo.seed-enabled 关闭）。
 */
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);
    private final DemoProperties demoProperties;
    private final PasswordEncoder passwordEncoder;
    private final SysUserMapper sysUserMapper;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;
    private final AgentDefinitionMapper agentDefinitionMapper;

    @Override
    public void run(ApplicationArguments args) {
        if (!demoProperties.isSeedEnabled()) {
            log.info("Demo seed skipped (insighthub.demo.seed-enabled=false)");
            return;
        }
        String password = demoProperties.getPassword();
        if (password == null || password.length() < 12) {
            throw new IllegalStateException(
                    "DEMO_PASSWORD must contain at least 12 characters when demo seed is enabled");
        }
        String hash = passwordEncoder.encode(password);
        seedUser(demoProperties.getUserId(), "demo", "demo@insighthub.local", "Demo User A", hash);
        seedUser(demoProperties.getUserBId(), "demob", "demob@insighthub.local", "Demo User B", hash);

        seedWorkspace(
                demoProperties.getWorkspaceId(),
                "Demo Workspace A",
                "Week2 isolation workspace A",
                demoProperties.getUserId(),
                "wm-demo-a");
        seedWorkspace(
                demoProperties.getWorkspaceBId(),
                "Demo Workspace B",
                "Week2 isolation workspace B",
                demoProperties.getUserBId(),
                "wm-demo-b");

        seedDefaultAgents(demoProperties.getWorkspaceId());
        seedDefaultAgents(demoProperties.getWorkspaceBId());
        // 不在日志中打印明文密码
        log.info("Demo seed ready. users=demo/demob workspaces={}/{}",
                demoProperties.getWorkspaceId(), demoProperties.getWorkspaceBId());
    }

    private void seedUser(String userId, String username, String email, String displayName, String hash) {
        SysUser existing = sysUserMapper.selectOneById(userId);
        if (existing == null) {
            SysUser user = new SysUser();
            user.setId(userId);
            user.setUsername(username);
            user.setPasswordHash(hash);
            user.setEmail(email);
            user.setDisplayName(displayName);
            user.setStatus(1);
            sysUserMapper.insert(user);
            log.info("Seeded user {}", username);
            return;
        }
        // 已存在账号可能已修改密码，启动过程不得覆盖用户凭据
        log.info("Demo user {} already exists; credentials unchanged", username);
    }

    private void seedWorkspace(
            String workspaceId, String name, String description, String ownerId, String memberId) {
        if (workspaceMapper.selectOneById(workspaceId) == null) {
            Workspace workspace = new Workspace();
            workspace.setId(workspaceId);
            workspace.setName(name);
            workspace.setDescription(description);
            workspace.setOwnerId(ownerId);
            workspace.setMaxConcurrentTasks(3);
            workspace.setMonthlyTokenQuota(1_000_000L);
            workspace.setStatus(1);
            workspaceMapper.insert(workspace);
            log.info("Seeded workspace {}", workspaceId);
        }

        if (workspaceMemberMapper.selectCountByQuery(QueryWrapper.create()
                .eq(WorkspaceMember::getWorkspaceId, workspaceId)
                .eq(WorkspaceMember::getUserId, ownerId)) == 0) {
            WorkspaceMember member = new WorkspaceMember();
            member.setId(memberId);
            member.setWorkspaceId(workspaceId);
            member.setUserId(ownerId);
            member.setRole("OWNER");
            workspaceMemberMapper.insert(member);
        }
    }

    private void seedDefaultAgents(String workspaceId) {
        seedAgent(workspaceId, "PLANNER", "Planner");
        seedAgent(workspaceId, "SUPERVISOR", "Supervisor");
        seedAgent(workspaceId, "WEB_RESEARCHER", "Web Researcher");
    }

    private void seedAgent(String workspaceId, String type, String name) {
        if (agentDefinitionMapper.selectCountByQuery(QueryWrapper.create()
                .eq(AgentDefinition::getWorkspaceId, workspaceId)
                .eq(AgentDefinition::getAgentType, type)) > 0) {
            return;
        }
        String id = "agent-" + workspaceId.replace("workspace-", "") + "-" + type.toLowerCase();
        if (id.length() > 64) {
            id = "agent-" + type.toLowerCase() + "-" + Integer.toHexString(workspaceId.hashCode());
        }
        AgentDefinition agent = new AgentDefinition();
        agent.setId(id);
        agent.setWorkspaceId(workspaceId);
        agent.setName(name);
        agent.setAgentType(type);
        agent.setRuntime("PYTHON");
        agent.setPromptVersion("v1");
        agent.setSystemPrompt("Default " + name + " for workspace " + workspaceId);
        agent.setEnabled(1);
        agent.setVersion(1);
        agentDefinitionMapper.insert(agent);
    }
}
