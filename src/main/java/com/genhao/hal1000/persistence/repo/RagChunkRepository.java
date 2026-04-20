package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.rag.RagChunkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunkEntity, String> {

    List<RagChunkEntity> findByConversationIdOrderByDocumentIdAscChunkIndexAsc(String conversationId);

    void deleteByDocumentId(String documentId);

    @Query("SELECT COUNT(c) FROM RagChunkEntity c WHERE c.documentId = :documentId")
    long countByDocumentId(@Param("documentId") String documentId);
}
