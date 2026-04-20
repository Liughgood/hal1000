package com.genhao.hal1000.rag;

/**
 * RAG extension point: injects extra system instructions (e.g. retrieved excerpts) before chat history.
 */
public interface ContextAugmentor {
    String augmentSystemPrompt(String conversationId, String userQuery);
}

