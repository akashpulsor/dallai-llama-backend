package com.dalai.llama.creativeplanning.service.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A small top-to-bottom text-flow writer over PDFBox -- handles page breaks and word-wrap so
 * {@link BrandPlanExportService} can just say "write this heading" / "write this paragraph"
 * without touching PDFBox's low-level content-stream API directly. Not a general-purpose PDF
 * library -- scoped to exactly the section/heading/paragraph shapes a branded plan export needs.
 */
class PdfDocumentWriter implements AutoCloseable {

    private static final float MARGIN = 50f;
    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    private final PDDocument document = new PDDocument();
    private final PDFont headingFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private final PDFont bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private PDPage currentPage;
    private PDPageContentStream stream;
    private float cursorY;

    PdfDocumentWriter() {
        newPage();
    }

    void title(String text) {
        writeWrapped(text, headingFont, 22, 30);
        cursorY -= 10;
    }

    void heading(String text) {
        ensureRoom(24);
        writeWrapped(text, headingFont, 14, 18);
        cursorY -= 4;
    }

    void paragraph(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        writeWrapped(text, bodyFont, 11, 15);
        cursorY -= 8;
    }

    void spacer() {
        cursorY -= 12;
    }

    byte[] toBytes() {
        try {
            stream.close();
            stream = null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void close() {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // best-effort close before document.close(); toBytes() already saved if reached
        }
        try {
            document.close();
        } catch (IOException ignored) {
        }
    }

    private void writeWrapped(String text, PDFont font, float fontSize, float leading) {
        for (String line : wrap(text, font, fontSize)) {
            ensureRoom(leading);
            try {
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(MARGIN, cursorY);
                stream.showText(line);
                stream.endText();
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            cursorY -= leading;
        }
    }

    private List<String> wrap(String text, PDFont font, float fontSize) {
        List<String> lines = new ArrayList<>();
        for (String paragraphLine : text.split("\n")) {
            StringBuilder current = new StringBuilder();
            for (String word : paragraphLine.split(" ")) {
                String candidate = current.isEmpty() ? word : current + " " + word;
                if (widthOf(candidate, font, fontSize) > CONTENT_WIDTH && !current.isEmpty()) {
                    lines.add(current.toString());
                    current = new StringBuilder(word);
                } else {
                    current = new StringBuilder(candidate);
                }
            }
            lines.add(current.toString());
        }
        return lines;
    }

    private float widthOf(String text, PDFont font, float fontSize) {
        try {
            return font.getStringWidth(text) / 1000 * fontSize;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void ensureRoom(float needed) {
        if (cursorY - needed < MARGIN) {
            newPage();
        }
    }

    private void newPage() {
        try {
            if (stream != null) {
                stream.close();
            }
            currentPage = new PDPage(PDRectangle.A4);
            document.addPage(currentPage);
            stream = new PDPageContentStream(document, currentPage);
            cursorY = PAGE_HEIGHT - MARGIN;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
