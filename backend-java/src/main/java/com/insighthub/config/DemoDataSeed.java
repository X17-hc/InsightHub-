package com.insighthub.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时写入演示用户与工作空间（若不存在）。
 */
@Component
public class DemoDataSeed implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeed.class);

    private final JdbcTemplate jdbcTemplate;
    private final DemoProperties demoProperties;

    public DemoDataSeed(JdbcTemplate jdbcTemplate, DemoProperties demoProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.demoProperties = demoProperties;
    }

    @Override
    public void run(ApplicationArguments args) {
        String userId = demoProperties.getUserId();
        String workspaceId = demoProperties.getWorkspaceId();

        Integer userCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE id = ?", Integer.class, userId);
        if (userCount != null && userCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO sys_user (id, username, password_hash, email, display_name, status)
                    VALUES (?, 'demo', '{noop}demo', 'demo@insighthub.local', 'Demo User', 1)
                    """,
                    userId);
            log.info("Seeded demo user {}", userId);
        }

        Integer wsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace WHERE id = ?", Integer.class, workspaceId);
        if (wsCount != null && wsCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workspace (id, name, description, owner_id, max_concurrent_tasks, monthly_token_quota, status)
                    VALUES (?, 'Demo Workspace', 'Week1 demo workspace', ?, 3, 1000000, 1)
                    """,
                    workspaceId, userId);
            log.info("Seeded demo workspace {}", workspaceId);
        }

        Integer memberCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workspace_member WHERE workspace_id = ? AND user_id = ?",
                Integer.class, workspaceId, userId);
        if (memberCount != null && memberCount == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO workspace_member (id, workspace_id, user_id, role)
                    VALUES (?, ?, ?, 'OWNER')
                    """,
                    "wm-demo", workspaceId, userId);
            log.info("Seeded workspace member relation");
        }
    }
}
