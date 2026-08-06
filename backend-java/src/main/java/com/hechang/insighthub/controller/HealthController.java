package com.hechang.insighthub.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 健康检查（无需登录）。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Health")
public class HealthController {

    @GetMapping("/health")
    @Operation(summary = "健康检查")
    public BaseResponse<Map<String, String>> health() {
        // 统一信封：{ code, data, message }
        return ResultUtils.success(Map.of("status", "ok"));
    }
}
