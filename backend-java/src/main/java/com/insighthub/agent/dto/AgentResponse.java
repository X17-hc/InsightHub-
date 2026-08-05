package com.insighthub.agent.dto;

/**
 * Agent 响应。
 */
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

    public AgentResponse() {
    }

    public AgentResponse(
            String id,
            String workspaceId,
            String name,
            String agentType,
            String runtime,
            String promptVersion,
            String systemPrompt,
            boolean enabled,
            int version) {
        this.id = id;
        this.workspaceId = workspaceId;
        this.name = name;
        this.agentType = agentType;
        this.runtime = runtime;
        this.promptVersion = promptVersion;
        this.systemPrompt = systemPrompt;
        this.enabled = enabled;
        this.version = version;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getRuntime() {
        return runtime;
    }

    public void setRuntime(String runtime) {
        this.runtime = runtime;
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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
