package com.hechang.insighthub.model.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private String id;
    private String workspaceId;
    private String name;
    private String agentType;
    private String runtime;
    private String promptVersion;
    private String systemPrompt;
    private boolean enabled;
    private int version;


}
