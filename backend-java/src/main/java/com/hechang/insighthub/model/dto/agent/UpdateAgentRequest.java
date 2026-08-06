package com.hechang.insighthub.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 更新 Agent 请求。
 */
@Data
public class UpdateAgentRequest {

    @NotBlank
    private String name;

    private String promptVersion;

    private String systemPrompt;
    
}
