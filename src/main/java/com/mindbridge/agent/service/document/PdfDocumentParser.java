package com.mindbridge.agent.service.document;

import com.mindbridge.agent.domain.SourceType;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(1)
/**
 * 按页抽取 PDF 文本。禁止对整份文档调用一次 getText，否则页码无法绑定到片段。
 */
public class PdfDocumentParser implements DocumentParser {

    static final String PDF_CONTENT_TYPE = "application/pdf";
    static final String EMPTY_PDF_MESSAGE = "PDF source has no usable text";
    static final String PDF_PARSE_FAILED_MESSAGE = "PDF text parsing failed";

    @Override
    public boolean supports(String filename, String contentType) {
        String name = DocumentNames.lower(filename);
        String type = DocumentNames.lower(contentType);
        return name.endsWith(".pdf") || PDF_CONTENT_TYPE.equals(type) || type.endsWith("/pdf");
    }

    @Override
    public SourceType sourceType() {
        return SourceType.PDF;
    }

    @Override
    public ParsedDocument parse(String filename, byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            List<ParsedSection> sections = new ArrayList<>();
            int order = 0;
            int offset = 0;
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(document);
                if (pageText == null || pageText.isBlank()) {
                    continue;
                }
                String trimmed = pageText.trim();
                int startOffset = offset;
                int endOffset = startOffset + trimmed.length();
                sections.add(new ParsedSection(order++, page, null, startOffset, endOffset, trimmed));
                offset = endOffset + 1;
            }
            if (sections.isEmpty()) {
                throw new DocumentParseException(EMPTY_PDF_MESSAGE);
            }
            return new ParsedDocument(documentTitle(document, filename), SourceType.PDF, List.copyOf(sections));
        } catch (DocumentParseException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DocumentParseException(PDF_PARSE_FAILED_MESSAGE, exception);
        }
    }

    private String documentTitle(PDDocument document, String filename) {
        PDDocumentInformation information = document.getDocumentInformation();
        if (information != null) {
            String title = information.getTitle();
            if (title != null && !title.isBlank()) {
                return title.trim();
            }
        }
        return DocumentNames.titleFromFilename(filename);
    }
}
