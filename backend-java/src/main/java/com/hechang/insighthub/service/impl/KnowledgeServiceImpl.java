package com.hechang.insighthub.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.hechang.insighthub.config.UploadProperties;
import com.hechang.insighthub.exception.BusinessException;
import com.hechang.insighthub.integration.KnowledgeIngestClient;
import com.hechang.insighthub.mapper.DocumentMapper;
import com.hechang.insighthub.mapper.KnowledgeBaseMapper;
import com.hechang.insighthub.model.dto.knowledge.CreateKnowledgeBaseRequest;
import com.hechang.insighthub.model.dto.knowledge.DocumentResponse;
import com.hechang.insighthub.model.dto.knowledge.KnowledgeBaseResponse;
import com.hechang.insighthub.model.entity.KbDocument;
import com.hechang.insighthub.model.entity.KnowledgeBase;
import com.hechang.insighthub.service.KnowledgeService;
import com.hechang.insighthub.service.WorkspaceAccessService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import com.mybatisflex.core.update.UpdateChain;
import lombok.RequiredArgsConstructor;

/**
 * 知识库与文档业务实现。
 */
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl extends ServiceImpl<KnowledgeBaseMapper, KnowledgeBase>
        implements KnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeServiceImpl.class);

    private static final String DEFAULT_EMBEDDING_MODEL = "text-embedding-3-small";
    private static final String DEFAULT_CHUNK_STRATEGY = "PARENT_CHILD";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DISABLED = "DISABLED";
    private static final String PARSE_PENDING = "PENDING";
    private static final String PARSE_PARSING = "PARSING";
    private static final String PARSE_INDEXED = "INDEXED";
    private static final String PARSE_FAILED = "FAILED";

    private final WorkspaceAccessService accessService;
    private final DocumentMapper documentMapper;
    private final UploadProperties uploadProperties;
    private final KnowledgeIngestClient ingestClient;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public KnowledgeBaseResponse create(String workspaceId, CreateKnowledgeBaseRequest request) {
        String userId = accessService.requireCurrentMember(workspaceId).userId();

        String id = "kb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        KnowledgeBase entity = new KnowledgeBase();
        entity.setId(id);
        entity.setWorkspaceId(workspaceId);
        entity.setName(request.getName().trim());
        entity.setDescription(request.getDescription());
        entity.setEmbeddingModel(DEFAULT_EMBEDDING_MODEL);
        entity.setChunkStrategy(DEFAULT_CHUNK_STRATEGY);
        entity.setStatus(STATUS_ACTIVE);
        entity.setDocCount(0);
        entity.setCreatedBy(userId);
        save(entity);

        return toKbResponse(requireKb(workspaceId, id));
    }

    @Override
    public List<KnowledgeBaseResponse> list(String workspaceId) {
        accessService.requireCurrentMember(workspaceId);
        return mapper.listByWorkspace(workspaceId)
                .stream().map(this::toKbResponse).toList();
    }

    @Override
    public KnowledgeBaseResponse get(String workspaceId, String kbId) {
        accessService.requireCurrentMember(workspaceId);
        KnowledgeBase kb = requireKb(workspaceId, kbId);
        return toKbResponse(kb);
    }

    @Override
    @Transactional
    public KnowledgeBaseResponse disable(String workspaceId, String kbId) {
        accessService.requireCurrentAdmin(workspaceId);
        requireKb(workspaceId, kbId);

        updateKnowledgeBaseStatus(kbId, workspaceId, STATUS_DISABLED);
        eventPublisher.publishEvent(new KnowledgeChunksDeleteRequested(workspaceId, kbId));
        return toKbResponse(requireKb(workspaceId, kbId));
    }

    @Override
    @Transactional
    public DocumentResponse uploadDocument(String workspaceId, String kbId, MultipartFile file) {
        String userId = accessService.requireCurrentMember(workspaceId).userId();
        KnowledgeBase kb = requireKb(workspaceId, kbId);
        if (!STATUS_ACTIVE.equals(kb.getStatus())) {
            throw BusinessException.conflict("KB_DISABLED", "knowledge base is disabled");
        }
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("EMPTY_FILE", "file is required");
        }

        String originalName = sanitizeFileName(file.getOriginalFilename());
        String ext = extensionOf(originalName);
        Set<String> allowed = Set.copyOf(
                uploadProperties.getAllowedExtensions().stream()
                        .map(e -> e.toLowerCase(Locale.ROOT))
                        .toList());
        if (!allowed.contains(ext)) {
            throw BusinessException.badRequest(
                    "INVALID_EXTENSION",
                    "allowed extensions: " + String.join(",", allowed));
        }
        if (file.getSize() > uploadProperties.getMaxBytes()) {
            throw BusinessException.badRequest(
                    "FILE_TOO_LARGE",
                    "max bytes: " + uploadProperties.getMaxBytes());
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new BusinessException(
                    com.hechang.insighthub.exception.ErrorCode.SYSTEM_ERROR,
                    "FILE_READ_FAILED: " + ex.getMessage());
        }
        String contentHash = sha256Hex(bytes);

        KbDocument dup = documentMapper.findByKnowledgeBaseAndContentHash(kbId, contentHash);
        if (dup != null) {
            throw BusinessException.conflict(
                    "DUPLICATE_DOCUMENT",
                    "same content already uploaded as " + dup.getId());
        }

        String docId = "doc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Path dest = Path.of(
                        uploadProperties.getRootDir(),
                        workspaceId,
                        kbId,
                        docId,
                        originalName)
                .toAbsolutePath()
                .normalize();
        try {
            Files.createDirectories(dest.getParent());
            Files.write(dest, bytes);
        } catch (IOException ex) {
            throw new BusinessException(
                    com.hechang.insighthub.exception.ErrorCode.SYSTEM_ERROR,
                    "FILE_WRITE_FAILED: " + ex.getMessage());
        }

        String contentType = resolveContentType(file.getContentType(), ext);
        KbDocument doc = new KbDocument();
        doc.setId(docId);
        doc.setKnowledgeBaseId(kbId);
        doc.setWorkspaceId(workspaceId);
        doc.setFileName(originalName);
        doc.setContentType(contentType);
        doc.setFileSize((long) bytes.length);
        doc.setContentHash(contentHash);
        doc.setSourceUri(dest.toString());
        doc.setParseStatus(PARSE_PENDING);
        doc.setChunkCount(0);
        doc.setUploadedBy(userId);
        documentMapper.insert(doc);

        int count = Math.toIntExact(documentMapper.countByKnowledgeBaseAndWorkspace(kbId, workspaceId));
        updateKnowledgeBaseDocCount(kbId, workspaceId, count);

        // 必须在事务提交后再异步入库，否则线程读不到未提交行
        scheduleIngestAfterCommit(workspaceId, kbId, docId);
        return toDocResponse(requireDoc(workspaceId, kbId, docId));
    }

    @Override
    public List<DocumentResponse> listDocuments(String workspaceId, String kbId) {
        accessService.requireCurrentMember(workspaceId);
        requireKb(workspaceId, kbId);
        return documentMapper.listByKnowledgeBaseAndWorkspace(kbId, workspaceId)
                .stream().map(this::toDocResponse).toList();
    }

    @Override
    public DocumentResponse getDocument(String workspaceId, String kbId, String docId) {
        accessService.requireCurrentMember(workspaceId);
        requireKb(workspaceId, kbId);
        return toDocResponse(requireDoc(workspaceId, kbId, docId));
    }

    @Override
    @Transactional
    public DocumentResponse reindex(String workspaceId, String kbId, String docId) {
        accessService.requireCurrentMember(workspaceId);
        KnowledgeBase kb = requireKb(workspaceId, kbId);
        if (!STATUS_ACTIVE.equals(kb.getStatus())) {
            throw BusinessException.conflict("KB_DISABLED", "knowledge base is disabled");
        }
        KbDocument doc = requireDoc(workspaceId, kbId, docId);
        updateDocumentParseStatus(doc.getId(), workspaceId, PARSE_PENDING, doc.getChunkCount(), null);
        scheduleIngestAfterCommit(workspaceId, kbId, docId);
        return toDocResponse(requireDoc(workspaceId, kbId, docId));
    }

    /** 发布事件；监听器会在事务提交后触发异步入库，避免读到未提交文档。 */
    private void scheduleIngestAfterCommit(String workspaceId, String kbId, String docId) {
        eventPublisher.publishEvent(new DocumentIngestRequested(workspaceId, kbId, docId));
    }

    @Override
    @Async("knowledgeIngestExecutor")
    public void ingestDocument(String workspaceId, String kbId, String documentId) {
        KbDocument doc = findDocument(workspaceId, documentId);
        if (doc == null || !kbId.equals(doc.getKnowledgeBaseId())) {
            log.warn("ingest skip: document not found documentId={} workspaceId={}", documentId, workspaceId);
            return;
        }
        // 已在解析中则跳过，避免并发重复调用 Python
        if (PARSE_PARSING.equals(doc.getParseStatus())) {
            log.info("ingest skip: already PARSING documentId={}", documentId);
            return;
        }

        updateDocumentParseStatus(documentId, workspaceId, PARSE_PARSING, doc.getChunkCount(), null);
        try {
            KnowledgeIngestClient.IngestDocumentResponse result = ingestClient.ingestDocument(
                    workspaceId,
                    kbId,
                    documentId,
                    doc.getSourceUri(),
                    doc.getContentType(),
                    doc.getFileName());
            int chunkCount = result == null ? 0 : result.getChunkCount();
            updateDocumentParseStatus(documentId, workspaceId, PARSE_INDEXED, chunkCount, null);
        } catch (Exception ex) {
            String msg = ex.getMessage() == null ? "ingest failed" : ex.getMessage();
            if (msg.length() > 1000) {
                msg = msg.substring(0, 1000);
            }
            log.warn("ingest failed documentId={}", documentId, ex);
            updateDocumentParseStatus(documentId, workspaceId, PARSE_FAILED, 0, msg);
        }
    }

    private KnowledgeBase requireKb(String workspaceId, String kbId) {
        KnowledgeBase kb = mapper.findByIdAndWorkspace(kbId, workspaceId);
        if (kb == null) {
            throw BusinessException.notFound("knowledge base not found");
        }
        return kb;
    }

    private KbDocument requireDoc(String workspaceId, String kbId, String docId) {
        KbDocument doc = findDocument(workspaceId, docId);
        if (doc == null || !kbId.equals(doc.getKnowledgeBaseId())) {
            throw BusinessException.notFound("document not found");
        }
        return doc;
    }

    private KbDocument findDocument(String workspaceId, String documentId) {
        return documentMapper.findByIdAndWorkspace(documentId, workspaceId);
    }

    private void updateDocumentParseStatus(
            String documentId, String workspaceId, String parseStatus, Integer chunkCount, String errorMessage) {
        UpdateChain.of(documentMapper)
                .set(KbDocument::getParseStatus, parseStatus)
                .set(KbDocument::getChunkCount, chunkCount)
                .set(KbDocument::getErrorMessage, errorMessage)
                .setRaw(KbDocument::getUpdatedAt, "NOW()")
                .eq(KbDocument::getId, documentId)
                .eq(KbDocument::getWorkspaceId, workspaceId)
                .update();
    }

    private void updateKnowledgeBaseStatus(String kbId, String workspaceId, String status) {
        UpdateChain.of(mapper)
                .set(KnowledgeBase::getStatus, status)
                .setRaw(KnowledgeBase::getUpdatedAt, "NOW()")
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getWorkspaceId, workspaceId)
                .update();
    }

    private void updateKnowledgeBaseDocCount(String kbId, String workspaceId, int docCount) {
        UpdateChain.of(mapper)
                .set(KnowledgeBase::getDocCount, docCount)
                .setRaw(KnowledgeBase::getUpdatedAt, "NOW()")
                .eq(KnowledgeBase::getId, kbId)
                .eq(KnowledgeBase::getWorkspaceId, workspaceId)
                .update();
    }
    private KnowledgeBaseResponse toKbResponse(KnowledgeBase row) {
        return new KnowledgeBaseResponse(
                row.getId(),
                row.getWorkspaceId(),
                row.getName(),
                row.getDescription(),
                row.getEmbeddingModel(),
                row.getChunkStrategy(),
                row.getStatus(),
                row.getDocCount() == null ? 0 : row.getDocCount(),
                row.getCreatedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    private DocumentResponse toDocResponse(KbDocument row) {
        return new DocumentResponse(
                row.getId(),
                row.getKnowledgeBaseId(),
                row.getWorkspaceId(),
                row.getFileName(),
                row.getContentType(),
                row.getFileSize(),
                row.getContentHash(),
                row.getSourceUri(),
                row.getParseStatus(),
                row.getChunkCount() == null ? 0 : row.getChunkCount(),
                row.getErrorMessage(),
                row.getUploadedBy(),
                row.getCreatedAt(),
                row.getUpdatedAt());
    }

    /** 去除路径分量，仅保留安全文件名。 */
    private static String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed.bin";
        }
        String name = Path.of(original).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "unnamed.bin" : name;
    }

    private static String extensionOf(String fileName) {
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private static String resolveContentType(String provided, String ext) {
        if (provided != null && !provided.isBlank()) {
            return provided;
        }
        return switch (ext) {
            case "txt" -> "text/plain";
            case "md", "markdown" -> "text/markdown";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
