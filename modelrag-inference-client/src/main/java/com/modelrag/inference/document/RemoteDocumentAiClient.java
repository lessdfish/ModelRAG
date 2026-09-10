package com.modelrag.inference.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import com.modelrag.knowledge.parser.ParseLimits;
import com.modelrag.knowledge.parser.ParsedDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Sends only a Java-owned temporary source file to the stateless document-AI service. */
@Service
public class RemoteDocumentAiClient implements DocumentAiClient {
    private final AiServiceClient client;
    private final ObjectMapper json;

    public RemoteDocumentAiClient(AiServiceClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public ParsedDocument parse(Path source, String logicalFileName, ParseLimits limits, Duration timeout) {
        if (source == null || !Files.isRegularFile(source) || logicalFileName == null || logicalFileName.isBlank()
                || limits == null) {
            throw new AiServiceException("文档 AI 请求参数无效", 400, false);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("logical_file_name", logicalFileName);
        fields.put("options", options(limits));
        return client.postMultipart("document_ai", "/v1/documents/parse", source, logicalFileName,
                contentType(logicalFileName), fields, ParsedDocument.class, timeout);
    }

    private String options(ParseLimits limits) {
        try {
            return json.writeValueAsString(Map.of("max_pages", limits.maxPages(),
                    "max_extracted_chars", limits.maxExtractedChars(), "ocr", true, "preserve_layout", true));
        } catch (JsonProcessingException error) {
            throw new AiServiceException("文档 AI 选项编码失败", 400, false);
        }
    }

    private String contentType(String fileName) {
        String lower = fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".md")) return "text/markdown";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }
}
