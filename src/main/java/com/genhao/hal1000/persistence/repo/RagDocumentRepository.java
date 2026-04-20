package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.rag.RagDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocumentEntity, String> {

    List<RagDocumentEntity> findByConversationIdOrderByCreatedAtDesc(String conversationId);

    Optional<RagDocumentEntity> findByIdAndConversationId(String id, String conversationId);

    void deleteByIdAndConversationId(String id, String conversationId);
}
