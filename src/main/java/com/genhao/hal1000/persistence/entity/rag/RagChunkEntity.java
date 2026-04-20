package com.genhao.hal1000.persistence.entity.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "rag_chunk")
@Getter
@Setter
@NoArgsConstructor
public class RagChunkEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "document_id", nullable = false, length = 36)
    private String documentId;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "text", nullable = false, columnDefinition = "clob")
    private String text;

    @Column(name = "embedding_dim", nullable = false)
    private int embeddingDim;

    @Column(name = "embedding", nullable = false, columnDefinition = "blob")
    private byte[] embedding;

    @PrePersist
    void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
    }
}
