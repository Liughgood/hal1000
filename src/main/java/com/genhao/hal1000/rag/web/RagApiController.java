package com.genhao.hal1000.rag.web;

import com.genhao.hal1000.auth.CurrentUser;
import com.genhao.hal1000.persistence.entity.rag.RagDocumentEntity;
import com.genhao.hal1000.persistence.repo.ChatConversationRepository;
import com.genhao.hal1000.persistence.repo.RagChunkRepository;
import com.genhao.hal1000.persistence.repo.RagDocumentRepository;
import com.genhao.hal1000.rag.RagIngestionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/conversations")
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "true")
public class RagApiController {

    private final RagIngestionService ingestionService;
    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;
    private final ChatConversationRepository conversationRepository;
    private final CurrentUser currentUser;

    public RagApiController(
            RagIngestionService ingestionService,
            RagDocumentRepository documentRepository,
            RagChunkRepository chunkRepository,
            ChatConversationRepository conversationRepository,
            CurrentUser currentUser
    ) {
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.conversationRepository = conversationRepository;
        this.currentUser = currentUser;
    }

    @PostMapping(path = "/{conversationId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public RagDocumentDto upload(
            @PathVariable("conversationId") String conversationId,
            @RequestParam("file") MultipartFile file
    ) {
        try {
            var doc = ingestionService.ingestUpload(currentUser.requireUserId(), conversationId, file);
            return toDto(doc);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping(path = "/{conversationId}/documents/from-url", consumes = MediaType.APPLICATION_JSON_VALUE)
    public RagDocumentDto fromUrl(
            @PathVariable("conversationId") String conversationId,
            @RequestBody FromUrlRequest body
    ) {
        try {
            var doc = ingestionService.ingestUrl(currentUser.requireUserId(), conversationId, body.url());
            return toDto(doc);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @GetMapping("/{conversationId}/documents")
    public List<RagDocumentDto> list(@PathVariable("conversationId") String conversationId) {
        requireConversationOwned(conversationId);
        return documentRepository.findByConversationIdOrderByCreatedAtDesc(conversationId).stream()
                .map(this::toDto)
                .toList();
    }

    @DeleteMapping("/{conversationId}/documents/{documentId}")
    public void delete(
            @PathVariable("conversationId") String conversationId,
            @PathVariable("documentId") String documentId
    ) {
        try {
            ingestionService.deleteDocument(currentUser.requireUserId(), conversationId, documentId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    private void requireConversationOwned(String conversationId) {
        var convo = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "conversation not found"));
        var userId = currentUser.requireUserId();
        if (convo.getUserId() == null || !convo.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "conversation not owned");
        }
    }

    private RagDocumentDto toDto(RagDocumentEntity d) {
        long chunks = chunkRepository.countByDocumentId(d.getId());
        return new RagDocumentDto(
                d.getId(),
                d.getFilename(),
                d.getSourceType().name(),
                d.getSourceUrl(),
                d.getStatus().name(),
                d.getCreatedAt(),
                chunks,
                d.getErrorMessage()
        );
    }

    public record FromUrlRequest(String url) {
    }

    public record RagDocumentDto(
            String id,
            String filename,
            String sourceType,
            String sourceUrl,
            String status,
            Long createdAt,
            long chunkCount,
            String errorMessage
    ) {
    }
}
