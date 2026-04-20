package com.genhao.hal1000.rag;

import com.genhao.hal1000.persistence.entity.rag.RagDocumentEntity;
import com.genhao.hal1000.persistence.entity.rag.RagDocumentStatus;
import com.genhao.hal1000.persistence.entity.rag.RagSourceType;
import com.genhao.hal1000.persistence.repo.ChatConversationRepository;
import com.genhao.hal1000.persistence.repo.RagChunkRepository;
import com.genhao.hal1000.persistence.repo.RagDocumentRepository;
import com.genhao.hal1000.rag.embedding.EmbeddingClient;
import com.genhao.hal1000.rag.util.PdfTextExtractor;
import org.jsoup.Jsoup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Service
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "true")
public class RagIngestionService {

    private final RagProperties ragProperties;
    private final EmbeddingClient embeddingClient;
    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;
    private final ChatConversationRepository conversationRepository;
    private final WebClient urlFetchClient;
    private final RagIndexingWorker indexingWorker;

    public RagIngestionService(
            RagProperties ragProperties,
            EmbeddingClient embeddingClient,
            RagDocumentRepository documentRepository,
            RagChunkRepository chunkRepository,
            ChatConversationRepository conversationRepository,
            RagIndexingWorker indexingWorker
    ) {
        this.ragProperties = ragProperties;
        this.embeddingClient = embeddingClient;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.conversationRepository = conversationRepository;
        this.indexingWorker = indexingWorker;
        int max = Math.max(64_000, ragProperties.getMaxUrlBytes());
        var strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(max))
                .build();
        this.urlFetchClient = WebClient.builder().exchangeStrategies(strategies).build();
    }

    @Transactional
    public RagDocumentEntity ingestUpload(String userId, String conversationId, MultipartFile file) {
        requireConversationOwned(userId, conversationId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("empty file");
        }
        String mime = file.getContentType() != null ? file.getContentType().strip() : "application/octet-stream";
        String name = file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank()
                ? file.getOriginalFilename()
                : "upload.txt";
        if (!isAllowedUpload(mime, name)) {
            throw new IllegalArgumentException(
                    "unsupported file: " + mime + " (use .txt / .md / .pdf, or matching Content-Type)");
        }
        final String raw;
        try {
            byte[] bytes = file.getBytes();
            if (looksLikePdf(mime, name)) {
                try {
                    raw = PdfTextExtractor.extractText(bytes);
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("PDF 解析失败: " + e.getMessage());
                }
            } else {
                raw = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取上传文件失败: " + e.getMessage());
        }
        return createAndEnqueue(conversationId, name, RagSourceType.upload, null, mime, raw);
    }

    @Transactional
    public RagDocumentEntity ingestUrl(String userId, String conversationId, String url) {
        requireConversationOwned(userId, conversationId);
        URI uri = parseHttpUrl(url);
        var resp = urlFetchClient.get()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, "HAL1000/1.0")
                .retrieve()
                .toEntity(byte[].class)
                .block();
        if (resp == null || resp.getBody() == null) {
            throw new IllegalStateException("empty response");
        }
        byte[] body = resp.getBody();
        if (body.length > ragProperties.getMaxUrlBytes()) {
            throw new IllegalArgumentException("response larger than max-url-bytes");
        }
        String mime = resp.getHeaders().getContentType() != null
                ? resp.getHeaders().getContentType().toString()
                : "";
        String raw = extractTextFromFetched(mime, body, uri.getPath());
        String filename = uri.getHost() != null ? uri.getHost() + (uri.getPath() != null ? uri.getPath() : "") : url;
        if (filename.length() > 500) {
            filename = filename.substring(0, 500);
        }
        return createAndEnqueue(conversationId, filename, RagSourceType.url, url, mime, raw);
    }

    private static URI parseHttpUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url required");
        }
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid url");
        }
        if (uri.getScheme() == null || !(uri.getScheme().equalsIgnoreCase("https") || uri.getScheme().equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("only http/https urls are allowed");
        }
        return uri;
    }

    private String extractTextFromFetched(String mime, byte[] body, String pathHint) {
        var m = mime != null ? mime.toLowerCase() : "";
        if (m.contains("pdf") || (pathHint != null && pathHint.toLowerCase().endsWith(".pdf"))) {
            try {
                return PdfTextExtractor.extractText(body);
            } catch (Exception e) {
                throw new IllegalArgumentException("PDF 解析失败: " + e.getMessage());
            }
        }
        var s = new String(body, StandardCharsets.UTF_8);
        if (m.contains("html")) {
            var doc = Jsoup.parse(s);
            return doc.body().text();
        }
        return s;
    }

    private RagDocumentEntity createAndEnqueue(
            String conversationId,
            String filename,
            RagSourceType sourceType,
            String sourceUrl,
            String mimeType,
            String rawText
    ) {
        var doc = new RagDocumentEntity();
        doc.setConversationId(conversationId);
        doc.setFilename(filename);
        doc.setSourceType(sourceType);
        doc.setSourceUrl(sourceUrl);
        doc.setMimeType(mimeType);
        doc.setStatus(RagDocumentStatus.processing);
        doc.setErrorMessage(null);
        documentRepository.save(doc);
        // Run async indexing only AFTER the current transaction commits; otherwise the async thread may not see
        // the newly inserted rag_document row and would exit early, leaving status stuck at processing.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            final String docId = doc.getId();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    indexingWorker.indexDocument(docId, conversationId, rawText);
                }
            });
        } else {
            indexingWorker.indexDocument(doc.getId(), conversationId, rawText);
        }
        return doc;
    }

    @Transactional
    public void deleteDocument(String userId, String conversationId, String documentId) {
        requireConversationOwned(userId, conversationId);
        var doc = documentRepository.findByIdAndConversationId(documentId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("document not found"));
        chunkRepository.deleteByDocumentId(doc.getId());
        documentRepository.delete(doc);
    }

    private void requireConversationOwned(String userId, String conversationId) {
        var convo = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));
        if (convo.getUserId() == null || !convo.getUserId().equals(userId)) {
            throw new AccessDeniedException("conversation not owned");
        }
    }

    /**
     * Allows text types, PDF, or generic octet-stream when filename suggests .pdf / .txt / .md.
     */
    private static boolean isAllowedUpload(String mime, String filename) {
        var m = mime != null ? mime.toLowerCase() : "";
        if (m.startsWith("text/plain") || m.startsWith("text/markdown")) {
            return true;
        }
        if (m.contains("pdf") || m.contains("application/x-pdf")) {
            return true;
        }
        if (m.contains("application/octet-stream") && filename != null) {
            var f = filename.toLowerCase();
            return f.endsWith(".pdf") || f.endsWith(".txt") || f.endsWith(".md");
        }
        return false;
    }

    private static boolean looksLikePdf(String mime, String filename) {
        var m = mime != null ? mime.toLowerCase() : "";
        if (m.contains("pdf") || m.contains("application/x-pdf")) {
            return true;
        }
        return filename != null && filename.toLowerCase().endsWith(".pdf");
    }
}
