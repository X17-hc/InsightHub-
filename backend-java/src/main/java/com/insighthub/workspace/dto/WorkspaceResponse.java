package com.insighthub.workspace.dto;

/**
 * 工作空间响应。
 */
public class WorkspaceResponse {

    private String id;
    private String name;
    private String description;
    private String ownerId;
    private int status;

    public WorkspaceResponse() {
    }

    public WorkspaceResponse(String id, String name, String description, String ownerId, int status) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.ownerId = ownerId;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }
}
