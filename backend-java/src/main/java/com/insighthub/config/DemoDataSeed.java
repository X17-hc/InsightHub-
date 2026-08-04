package com.insighthub.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.insighthub.agent.AgentRepository;

/**
 * 启动 seed：双用户 / 双工作空间 / 默认 Agent（可通过 insighthub.demo.seed-enabled 关闭）。
 */
@Component
public class DemoDataSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeed.class);
    private static final String DEMO_PASSWORD = "demo123456";

    private final JdbcTemplate jdbcTemplate;
    private final DemoProperties demoProperties;
    private final PasswordEncoder passwordEncoder;
    private final AgentRepository agentRepository;

    public DemoDataSeed(
            JdbcTemplate jdbcTemplate,
            DemoProperties demoProperties,
            PasswordEncoder passwordEncoder,
            AgentRepository agentRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.demoProperties = demoProperties;
        this.passwordEncoder = passwordEncoder;
        this.agentRepository = agentRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoProperties.isSeedEnabled()) {
            log.info("Demo seed skipped (insighthub.demo.seed-enabled=false)");
            return;
        }
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
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
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = ?", Integer.class, userId);
        if (count != null && count == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO sys_user (id, username, password_hash, email, display_name, status)
                    VALUES (?, ?, ?, ?, ?, 1)
                    """,
                    userId, username, hash, email, displayName);
            log.info("Seeded user {}", username);
            return;
        }
        // 仅当仍是非 BCrypt 哈希时升级一次，避免每次启动重置用户改过的密码
        String existing = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM sys_user WHERE id = ?", String.class, userId);
        if (existing == null || !existing.startsWith("$2")) {
            jdbcTemplate.update(
                    "UPDATE sys_user SET password_hash = ?, status = 1 WHERE id = ?",
                    hash, userId);
            log.info("Upgraded legacy password hash for user {}", username);
        }
    }

    private void seedWorkspace(String workspaceId, String name, String description, String ownerId, String memberId) {
        Integer wsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?", Integer.class, workspaceId);
        if (wsCount != null && wsCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workspace (id, name, description, owner_id, max_concurrent_tasks, monthly_token_quota, status)
                    VALUES (?, ?, ?, ?, 3, 1000000, 1)
                    """,
                    workspaceId, name, description, ownerId);
            log.info("Seeded workspace {}", workspaceId);
        }

        Integer memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspaceId, ownerId);
        if (memberCount != null && memberCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workspace_member (id, workspace_id, user_id, role)
                    VALUES (?, ?, ?, 'OWNER')
                    """,
                    memberId, workspaceId, ownerId);
        }
    }

    private void seedDefaultAgents(String workspaceId) {
        seedAgent(workspaceId, "PLANNER", "Planner");
        seedAgent(workspaceId, "SUPERVISOR", "Supervisor");
        seedAgent(workspaceId, "WEB_RESEARCHER", "Web Researcher");
    }

    private void seedAgent(String workspaceId, String type, String name) {
        if (agentRepository.existsType(workspaceId, type)) {
            return;
        }
        String id = "agent-" + workspaceId.replace("workspace-", "") + "-" + type.toLowerCase();
        if (id.length() > 64) {
            id = "agent-" + type.toLowerCase() + "-" + Integer.toHexString(workspaceId.hashCode());
        }
        agentRepository.insert(
                id,
                workspaceId,
                name,
                type,
                "PYTHON",
                "v1",
                "Default " + name + " for workspace " + workspaceId,
                true,
                1);
    }
}
