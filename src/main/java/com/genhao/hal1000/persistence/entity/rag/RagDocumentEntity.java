package com.genhao.hal1000.persistence.entity.rag;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Entity
@Table(name = "rag_document")
@Getter
@Setter
@NoArgsConstructor
public class RagDocumentEntity {

    @Id
    @Column(name = "id", nullable = false, length = 36)
    private String id;

    @Column(name = "conversation_id", nullable = false, length = 36)
    private String conversationId;

    @Column(name = "filename", nullable = false, length = 512)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private RagSourceType sourceType;

    @Column(name = "source_url", length = 2048)
    private String sourceUrl;

    @Column(name = "mime_type", length = 128)
    private String mimeType;

    @Column(name = "created_at", nullable = false)
    private Long createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RagDocumentStatus status;

    @Column(name = "error_message", columnDefinition = "clob")
    private String errorMessage;

    @PrePersist
    void prePersist() {
        if (this.id == null || this.id.isBlank()) {
            this.id = UUID.randomUUID().toString();
        }
        if (this.createdAt == null) {
            this.createdAt = System.currentTimeMillis();
        }
    }
}
