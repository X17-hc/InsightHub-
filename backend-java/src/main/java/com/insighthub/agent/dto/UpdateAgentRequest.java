package com.insighthub.agent.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 更新 Agent 请求。
 */
public class UpdateAgentRequest {

    @NotBlank
    private String name;

    private String promptVersion;

    private String systemPrompt;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
