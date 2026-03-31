package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.ChatMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, String> {
    List<ChatMessageEntity> findByConversation_IdOrderByCreatedAtAsc(String conversationId);
}

