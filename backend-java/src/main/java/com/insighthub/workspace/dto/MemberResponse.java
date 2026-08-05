package com.insighthub.workspace.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 成员响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MemberResponse {

    private String id;
    private String workspaceId;
    private String userId;
    private String role;
    private String username;
    private String displayName;

}
