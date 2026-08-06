package com.hechang.insighthub.model.dto.agent;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建 Agent 请求。
 */
@Data
public class CreateAgentRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String agentType;

    private String runtime = "PYTHON";

    private String promptVersion = "v1";

    private String systemPrompt;

    private boolean enabled = true;

}
