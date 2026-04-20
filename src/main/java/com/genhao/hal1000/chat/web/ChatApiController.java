package com.genhao.hal1000.chat.web;

import com.genhao.hal1000.chat.service.ChatService;
import com.genhao.hal1000.auth.CurrentUser;
import com.genhao.hal1000.llm.LlmErrorUtil;
import com.genhao.hal1000.llm.LlmGateway;
import com.genhao.hal1000.persistence.entity.ChatConversationEntity;
import com.genhao.hal1000.persistence.entity.ChatMessageEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ChatApiController {

    private final ChatService chatService;
    private final CurrentUser currentUser;

    public ChatApiController(ChatService chatService, CurrentUser currentUser) {
        this.chatService = chatService;
        this.currentUser = currentUser;
    }

    @PostMapping("/conversations")
    public ChatConversationEntity createConversation() {
        return chatService.createConversation(currentUser.requireUserId());
    }

    @GetMapping("/conversations")
    public List<ChatConversationEntity> listConversations() {
        return chatService.listConversations(currentUser.requireUserId());
    }

    @GetMapping("/conversations/{id}/messages")
    public List<MessageDto> listMessages(@PathVariable("id") String conversationId) {
        var entities = chatService.listMessages(currentUser.requireUserId(), conversationId);
        var out = new ArrayList<MessageDto>(entities.size());
        for (var m : entities) {
            out.add(MessageDto.from(m));
        }
        return out;
    }

    public record MessageDto(
            String id,
            String role,
            String content,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long createdAt
    ) {
        static MessageDto from(ChatMessageEntity m) {
            return new MessageDto(
                    m.getId(),
                    m.getRole().name(),
                    m.getContent(),
                    m.getModel(),
                    m.getPromptTokens(),
                    m.getCompletionTokens(),
                    m.getTotalTokens(),
                    m.getCreatedAt()
            );
        }
    }

    public record StreamRequest(String content) {
    }

    @PostMapping(path = "/conversations/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable("id") String conversationId, @RequestBody StreamRequest req) {
        var emitter = new SseEmitter(0L);
        try {
            chatService.streamReply(currentUser.requireUserId(), conversationId, req.content())
                    .subscribe(
                            evt -> sendDelta(emitter, evt),
                            err -> {
                                sendError(emitter, err);
                                complete(emitter);
                            },
                            () -> complete(emitter)
                    );
        } catch (Exception e) {
            sendError(emitter, e);
            complete(emitter);
        }
        return emitter;
    }

    private static void sendDelta(SseEmitter emitter, LlmGateway.StreamEvent evt) {
        if (evt.delta() == null || evt.delta().isBlank()) {
            return;
        }
        try {
            emitter.send(Map.of("type", "message_delta", "delta", evt.delta()));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static void sendError(SseEmitter emitter, Throwable err) {
        try {
            emitter.send(Map.of("type", "error", "error", LlmErrorUtil.toFriendlyError(err)));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private static void complete(SseEmitter emitter) {
        try {
            emitter.send("[DONE]");
        } catch (IOException ignored) {
        } finally {
            emitter.complete();
        }
    }
}

