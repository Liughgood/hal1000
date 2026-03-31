package com.genhao.hal1000.persistence.repo;

import com.genhao.hal1000.persistence.entity.ChatConversationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatConversationRepository extends JpaRepository<ChatConversationEntity, String> {
}

