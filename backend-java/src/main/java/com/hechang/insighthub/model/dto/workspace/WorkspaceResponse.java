package com.hechang.insighthub.model.dto.workspace;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作空间响应。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class WorkspaceResponse {

    private String id;
    private String name;
    private String description;
    private String ownerId;
    private int status;

}
