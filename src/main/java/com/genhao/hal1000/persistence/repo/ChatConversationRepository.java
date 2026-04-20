package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.ChatConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, String> {
    List<ChatConversationEntity> findByUserId(String userId);

    Optional<ChatConversationEntity> findByIdAndUserId(String id, String userId);
}

