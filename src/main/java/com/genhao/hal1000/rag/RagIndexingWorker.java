package com.genhao.hal1000.rag;

import com.genhao.hal1000.persistence.entity.rag.RagChunkEntity;
import com.genhao.hal1000.persistence.entity.rag.RagDocumentStatus;
import com.genhao.hal1000.persistence.repo.RagChunkRepository;
import com.genhao.hal1000.persistence.repo.RagDocumentRepository;
import com.genhao.hal1000.rag.embedding.EmbeddingClient;
import com.genhao.hal1000.rag.util.FloatEmbeddingCodec;
import com.genhao.hal1000.rag.util.TextChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "true")
public class RagIndexingWorker {

    private static final Logger log = LoggerFactory.getLogger(RagIndexingWorker.class);

    private final RagProperties props;
    private final EmbeddingClient embeddingClient;
    private final RagDocumentRepository documentRepository;
    private final RagChunkRepository chunkRepository;

    public RagIndexingWorker(
            RagProperties props,
            EmbeddingClient embeddingClient,
            RagDocumentRepository documentRepository,
            RagChunkRepository chunkRepository
    ) {
        this.props = props;
        this.embeddingClient = embeddingClient;
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
    }

    @Async
    @Transactional
    public void indexDocument(String documentId, String conversationId, String rawText) {
        var docOpt = documentRepository.findById(documentId);
        if (docOpt.isEmpty()) {
            return;
        }
        var doc = docOpt.get();
        if (!conversationId.equals(doc.getConversationId())) {
            return;
        }
        if (doc.getStatus() != RagDocumentStatus.processing) {
            return;
        }

        try {
            String text = rawText == null ? "" : rawText;
            int maxChars = Math.max(10_000, props.getMaxExtractChars());
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars);
            }

            var chunks = TextChunker.chunk(text, props.getChunkSizeChars(), props.getChunkOverlapChars());
            int maxChunks = Math.max(1, props.getMaxChunks());
            if (chunks.size() > maxChunks) {
                chunks = chunks.subList(0, maxChunks);
            }
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("no text to index (PDF may be image-only)");
            }

            // If re-indexing, clear old chunks first
            chunkRepository.deleteByDocumentId(documentId);

            List<float[]> vectors = embeddingClient.embed(chunks);
            if (vectors.size() != chunks.size()) {
                throw new IllegalStateException("embedding count mismatch");
            }

            var entities = new ArrayList<RagChunkEntity>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                float[] vec = vectors.get(i);
                var ch = new RagChunkEntity();
                ch.setDocumentId(documentId);
                ch.setConversationId(conversationId);
                ch.setChunkIndex(i);
                ch.setText(chunks.get(i));
                ch.setEmbeddingDim(vec.length);
                ch.setEmbedding(FloatEmbeddingCodec.floatsToBytesLittleEndian(vec));
                entities.add(ch);
            }
            chunkRepository.saveAll(entities);

            doc.setStatus(RagDocumentStatus.ready);
            doc.setErrorMessage(null);
            documentRepository.save(doc);
            log.info("rag.index.done documentId={} conversationId={} chunks={}", documentId, conversationId, entities.size());
        } catch (Exception e) {
            doc.setStatus(RagDocumentStatus.failed);
            doc.setErrorMessage(e.getMessage());
            documentRepository.save(doc);
            log.warn("rag.index.failed documentId={} conversationId={} err={}", documentId, conversationId, e.toString());
        }
    }
}

