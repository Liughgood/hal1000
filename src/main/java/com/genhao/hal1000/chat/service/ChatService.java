package com.genhao.hal1000.chat.service;

import com.genhao.hal1000.llm.LlmGateway;
import com.genhao.hal1000.persistence.entity.ChatConversationEntity;
import com.genhao.hal1000.persistence.entity.ChatMessageEntity;
import com.genhao.hal1000.persistence.entity.ChatRole;
import com.genhao.hal1000.persistence.repo.ChatConversationRepository;
import com.genhao.hal1000.persistence.repo.ChatMessageRepository;
import com.genhao.hal1000.rag.ContextAugmentor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final ChatConversationRepository conversationRepo;
    private final ChatMessageRepository messageRepo;
    private final LlmGateway llm;
    private final ContextAugmentor contextAugmentor;

    public ChatService(ChatConversationRepository conversationRepo,
                       ChatMessageRepository messageRepo,
                       LlmGateway llm,
                       ContextAugmentor contextAugmentor) {
        this.conversationRepo = conversationRepo;
        this.messageRepo = messageRepo;
        this.llm = llm;
        this.contextAugmentor = contextAugmentor;
    }

    @Transactional
    public ChatConversationEntity createConversation() {
        var c = new ChatConversationEntity();
        c.setTitle(null);
        c.setUserId(null);
        return conversationRepo.save(c);
    }

    public List<ChatConversationEntity> listConversations() {
        var all = conversationRepo.findAll();
        all.sort(Comparator.comparing(ChatConversationEntity::getUpdatedAt).reversed());
        return all;
    }

    public List<ChatMessageEntity> listMessages(String conversationId) {
        return messageRepo.findByConversation_IdOrderByCreatedAtAsc(conversationId);
    }

    @Transactional
    public Flux<LlmGateway.StreamEvent> streamReply(String conversationId, String userContent) {
        var metrics = new RequestMetrics();
        metrics.setStartedAtMs(System.currentTimeMillis());

        var convo = conversationRepo.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("conversation not found: " + conversationId));

        var userMsg = new ChatMessageEntity();
        userMsg.setConversation(convo);
        userMsg.setRole(ChatRole.user);
        userMsg.setContent(userContent);
        messageRepo.save(userMsg);

        // build context (MVP: full history)
        var history = messageRepo.findByConversation_IdOrderByCreatedAtAsc(conversationId);
        var llmMsgs = new ArrayList<LlmGateway.SimpleMessage>(history.size());

        // reserved for RAG (M1 no-op)
        var extraSystem = contextAugmentor.augmentSystemPrompt(conversationId, userContent);
        if (extraSystem != null && !extraSystem.isBlank()) {
            llmMsgs.add(new LlmGateway.SimpleMessage("system", extraSystem));
        }

        for (var m : history) {
            llmMsgs.add(new LlmGateway.SimpleMessage(m.getRole().name(), m.getContent()));
        }

        var assistantMsg = new ChatMessageEntity();
        assistantMsg.setConversation(convo);
        assistantMsg.setRole(ChatRole.assistant);
        assistantMsg.setContent("");
        messageRepo.save(assistantMsg);

        var sb = new StringBuilder();

        return llm.streamReply(llmMsgs)
                .doOnNext(evt -> {
                    if (evt.delta() != null) {
                        if (metrics.getFirstTokenAtMs() == 0) {
                            metrics.setFirstTokenAtMs(System.currentTimeMillis());
                        }
                        sb.append(evt.delta());
                    }
                })
                .doOnError(err -> {
                    metrics.setError(err.getMessage());
                    metrics.setEndedAtMs(System.currentTimeMillis());
                    log.warn("chat.stream.error conversationId={} latencyMs={} ttfbMs={} err={}",
                            conversationId, metrics.getLatencyMs(), metrics.getTtfbMs(), err.toString());
                })
                .doOnComplete(() -> {
                    metrics.setEndedAtMs(System.currentTimeMillis());
                    assistantMsg.setContent(sb.toString());
                    messageRepo.save(assistantMsg);
                    // trigger updatedAt
                    convo.setUpdatedAt(convo.getUpdatedAt());
                    conversationRepo.save(convo);

                    log.info("chat.stream.done conversationId={} latencyMs={} ttfbMs={} tokensTotal={}",
                            conversationId, metrics.getLatencyMs(), metrics.getTtfbMs(), metrics.getTotalTokens());
                });
    }
}

