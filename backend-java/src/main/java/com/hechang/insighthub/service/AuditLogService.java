package com.hechang.insighthub.service;

import java.util.Map;

public interface AuditLogService {
    void record(String workspaceId, String userId, String action, String resourceType,
                String resourceId, Map<String, Object> detail, String ip);
}
