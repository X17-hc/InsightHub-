package com.hechang.insighthub.controller;

import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.hechang.insighthub.common.BaseResponse;
import com.hechang.insighthub.common.ResultUtils;
import com.hechang.insighthub.model.dto.knowledge.CreateKnowledgeBaseRequest;
import com.hechang.insighthub.model.dto.knowledge.DocumentResponse;
import com.hechang.insighthub.model.dto.knowledge.KnowledgeBaseResponse;
import com.hechang.insighthub.service.KnowledgeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * 工作空间知识库 API。
 */
@RestController
@RequestMapping("/api/v1/workspaces/{workspaceId}/knowledge-bases")
@Validated
@Tag(name = "Knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping
    @Operation(summary = "创建知识库")
    public BaseResponse<KnowledgeBaseResponse> create(
            @PathVariable String workspaceId,
            @Valid @RequestBody CreateKnowledgeBaseRequest request) {
        return ResultUtils.success(knowledgeService.create(workspaceId, request));
    }

    @GetMapping
    @Operation(summary = "知识库列表")
    public BaseResponse<List<KnowledgeBaseResponse>> list(@PathVariable String workspaceId) {
        return ResultUtils.success(knowledgeService.list(workspaceId));
    }

    @GetMapping("/{kbId}")
    @Operation(summary = "知识库详情")
    public BaseResponse<KnowledgeBaseResponse> get(
            @PathVariable String workspaceId,
            @PathVariable String kbId) {
        return ResultUtils.success(knowledgeService.get(workspaceId, kbId));
    }

    @DeleteMapping("/{kbId}")
    @Operation(summary = "禁用知识库（软删除并清理向量）")
    public BaseResponse<KnowledgeBaseResponse> disable(
            @PathVariable String workspaceId,
            @PathVariable String kbId) {
        return ResultUtils.success(knowledgeService.disable(workspaceId, kbId));
    }

    @PostMapping(value = "/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "上传文档")
    public BaseResponse<DocumentResponse> uploadDocument(
            @PathVariable String workspaceId,
            @PathVariable String kbId,
            @RequestPart("file") MultipartFile file) {
        return ResultUtils.success(knowledgeService.uploadDocument(workspaceId, kbId, file));
    }

    @GetMapping("/{kbId}/documents")
    @Operation(summary = "文档列表")
    public BaseResponse<List<DocumentResponse>> listDocuments(
            @PathVariable String workspaceId,
            @PathVariable String kbId) {
        return ResultUtils.success(knowledgeService.listDocuments(workspaceId, kbId));
    }

    @GetMapping("/{kbId}/documents/{docId}")
    @Operation(summary = "文档详情")
    public BaseResponse<DocumentResponse> getDocument(
            @PathVariable String workspaceId,
            @PathVariable String kbId,
            @PathVariable String docId) {
        return ResultUtils.success(knowledgeService.getDocument(workspaceId, kbId, docId));
    }

    @PostMapping("/{kbId}/documents/{docId}/reindex")
    @Operation(summary = "重新索引文档")
    public BaseResponse<DocumentResponse> reindex(
            @PathVariable String workspaceId,
            @PathVariable String kbId,
            @PathVariable String docId) {
        return ResultUtils.success(knowledgeService.reindex(workspaceId, kbId, docId));
    }
}
