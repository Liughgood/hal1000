package com.genhao.hal1000.rag.util;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * Extracts plain text from PDF bytes using Apache PDFBox (layout order).
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    public static String extractText(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new IllegalArgumentException("empty PDF");
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            if (text == null) {
                return "";
            }
            return text.strip();
        }
    }
}
