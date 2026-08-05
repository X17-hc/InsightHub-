package com.insighthub.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 添加成员请求。
 */
@Data
public class AddMemberRequest {

    @NotBlank
    private String userId;

    /** OWNER / ADMIN / MEMBER，默认 MEMBER */
    private String role = "MEMBER";


}
