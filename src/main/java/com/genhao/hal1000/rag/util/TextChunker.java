package com.genhao.hal1000.rag.util;

import java.util.ArrayList;
import java.util.List;

public final class TextChunker {

    private TextChunker() {
    }

    /**
     * Fixed-size character windows with overlap. Trims empty chunks.
     */
    public static List<String> chunk(String text, int chunkSizeChars, int overlapChars) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var t = text.strip();
        if (chunkSizeChars <= 0) {
            return List.of(t);
        }
        int step = Math.max(1, chunkSizeChars - Math.max(0, overlapChars));
        var out = new ArrayList<String>();
        for (int start = 0; start < t.length(); start += step) {
            int end = Math.min(t.length(), start + chunkSizeChars);
            var piece = t.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= t.length()) {
                break;
            }
        }
        return out;
    }
}
