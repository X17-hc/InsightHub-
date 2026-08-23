package com.hechang.insighthub.service.impl;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hechang.insighthub.mapper.AuditLogMapper;
import com.hechang.insighthub.model.entity.AuditLog;
import com.hechang.insighthub.service.AuditLogService;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl extends ServiceImpl<AuditLogMapper, AuditLog> implements AuditLogService {
    private final ObjectMapper objectMapper;

    @Override
    public void record(String workspaceId, String userId, String action, String resourceType,
                       String resourceId, Map<String, Object> detail, String ip) {
        try {
            AuditLog row = new AuditLog();
            row.setWorkspaceId(workspaceId); row.setUserId(userId); row.setAction(action);
            row.setResourceType(resourceType); row.setResourceId(resourceId);
            row.setDetailJson(objectMapper.writeValueAsString(detail)); row.setIp(ip);
            row.setCreatedAt(LocalDateTime.now());
            save(row);
        } catch (Exception ex) {
            throw new IllegalStateException("write audit log failed", ex);
        }
    }
}
