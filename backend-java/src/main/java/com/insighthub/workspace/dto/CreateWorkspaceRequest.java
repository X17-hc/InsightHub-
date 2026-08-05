package com.insighthub.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建工作空间请求。
 */
@Data
public class CreateWorkspaceRequest {

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

}
