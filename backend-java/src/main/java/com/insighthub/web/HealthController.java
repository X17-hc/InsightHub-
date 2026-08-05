package com.insighthub.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}
