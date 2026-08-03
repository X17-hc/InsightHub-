package com.insighthub.web;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.insighthub.task.ResearchTaskService;
import com.insighthub.web.dto.AgentTaskResponseDto;
import com.insighthub.web.dto.CreateResearchTaskRequest;

import jakarta.validation.Valid;

/**
 * 第 1 周对外研究任务 API。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ResearchTaskController {

    private final ResearchTaskService researchTaskService;

    public ResearchTaskController(ResearchTaskService researchTaskService) {
        this.researchTaskService = researchTaskService;
    }

    /**
     * 健康检查。
     */
    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(java.util.Map.of("status", "ok"));
    }

    /**
     * 提交研究问题，同步返回 Markdown 报告与事件。
     */
    @PostMapping("/research/tasks")
    public ResponseEntity<AgentTaskResponseDto> create(@Valid @RequestBody CreateResearchTaskRequest request) {
        AgentTaskResponseDto response = researchTaskService.createAndRun(request.getQuery());
        return ResponseEntity.ok(response);
    }
}
