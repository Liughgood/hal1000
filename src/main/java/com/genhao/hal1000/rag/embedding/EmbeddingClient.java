package com.genhao.hal1000.rag.embedding;

import java.util.List;

public interface EmbeddingClient {

    /**
     * @return one float[] per input text, same order and length as {@code texts}.
     */
    List<float[]> embed(List<String> texts);
}
