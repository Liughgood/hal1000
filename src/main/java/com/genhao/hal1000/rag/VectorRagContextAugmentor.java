package com.genhao.hal1000.rag;

import com.genhao.hal1000.persistence.entity.rag.RagChunkEntity;
import com.genhao.hal1000.persistence.repo.RagChunkRepository;
import com.genhao.hal1000.persistence.repo.RagDocumentRepository;
import com.genhao.hal1000.rag.embedding.EmbeddingClient;
import com.genhao.hal1000.rag.util.FloatEmbeddingCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@Primary
@ConditionalOnProperty(name = "hal1000.rag.enabled", havingValue = "true")
public class VectorRagContextAugmentor implements ContextAugmentor {

    private static final Logger log = LoggerFactory.getLogger(VectorRagContextAugmentor.class);

    private final RagChunkRepository chunkRepository;
    private final RagDocumentRepository documentRepository;
    private final EmbeddingClient embeddingClient;
    private final RagProperties ragProperties;

    public VectorRagContextAugmentor(
            RagChunkRepository chunkRepository,
            RagDocumentRepository documentRepository,
            EmbeddingClient embeddingClient,
            RagProperties ragProperties
    ) {
        this.chunkRepository = chunkRepository;
        this.documentRepository = documentRepository;
        this.embeddingClient = embeddingClient;
        this.ragProperties = ragProperties;
    }

    @Override
    public String augmentSystemPrompt(String conversationId, String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return "";
        }
        List<RagChunkEntity> all;
        try {
            all = chunkRepository.findByConversationIdOrderByDocumentIdAscChunkIndexAsc(conversationId);
        } catch (Exception e) {
            log.warn("rag.load_chunks.failed conversationId={} err={}", conversationId, e.toString());
            return "";
        }
        if (all.isEmpty()) {
            return "";
        }

        final float[] queryVec;
        try {
            queryVec = embeddingClient.embed(List.of(userQuery)).get(0);
        } catch (Exception e) {
            log.warn("rag.query_embed.failed conversationId={} err={}", conversationId, e.toString());
            return "";
        }

        record Scored(RagChunkEntity chunk, double score) {
        }
        var scored = new ArrayList<Scored>();
        for (var ch : all) {
            try {
                float[] v = FloatEmbeddingCodec.bytesToFloatsLittleEndian(ch.getEmbedding());
                if (v.length != queryVec.length) {
                    log.debug("rag.skip_dim_mismatch chunkId={} chunkDim={} queryDim={}", ch.getId(), v.length, queryVec.length);
                    continue;
                }
                double s = cosineSimilarity(queryVec, v);
                scored.add(new Scored(ch, s));
            } catch (Exception e) {
                log.debug("rag.skip_chunk chunkId={} err={}", ch.getId(), e.toString());
            }
        }
        if (scored.isEmpty()) {
            return "";
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        int k = Math.max(1, ragProperties.getTopK());
        var top = scored.subList(0, Math.min(k, scored.size()));

        var docIds = top.stream().map(s -> s.chunk().getDocumentId()).distinct().toList();
        var docs = documentRepository.findAllById(docIds);
        Map<String, String> docTitle = new HashMap<>();
        for (var d : docs) {
            docTitle.put(d.getId(), d.getFilename() != null ? d.getFilename() : d.getId());
        }

        var sb = new StringBuilder();
        sb.append("You may use the numbered excerpts below from the user's knowledge base for this conversation. ")
                .append("When you rely on a passage, cite it in your answer using the same bracket label, e.g. [1] or [2].\n\n");

        int maxChars = Math.max(500, ragProperties.getMaxPromptChars());
        int used = 0;
        int n = 1;
        for (var s : top) {
            var ch = s.chunk();
            String title = docTitle.getOrDefault(ch.getDocumentId(), ch.getDocumentId());
            var block = "[" + n + "] (document: " + title + ", chunk " + ch.getChunkIndex() + "):\n" + ch.getText() + "\n\n";
            if (used + block.length() > maxChars) {
                break;
            }
            sb.append(block);
            used += block.length();
            n++;
        }
        if (n == 1) {
            return "";
        }
        return sb.toString().strip();
    }

    private static double cosineSimilarity(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            double x = a[i];
            double y = b[i];
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
