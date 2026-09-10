package com.modelrag.inference.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Stateless OCR adapter. It never accepts storage credentials or a storage object key. */
@Service
public class RemoteOcrClient implements OcrClient {
    private final AiServiceClient client;
    private final ObjectMapper json;

    public RemoteOcrClient(AiServiceClient client, ObjectMapper json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public OcrResponse ocr(Path source, String contentType, List<String> languageHints, Integer pageNumber,
            Duration timeout) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new AiServiceException("OCR 文件不存在", 400, false);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("language_hints", encodeLanguages(languageHints));
        if (pageNumber != null) fields.put("page_number", Integer.toString(pageNumber));
        return client.postMultipart("ocr", "/v1/ocr", source, source.getFileName().toString(),
                contentType, fields, OcrResponse.class, timeout);
    }

    private String encodeLanguages(List<String> languageHints) {
        List<String> safe = languageHints == null ? List.of() : languageHints.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).limit(16).toList();
        try {
            return json.writeValueAsString(safe);
        } catch (JsonProcessingException error) {
            throw new AiServiceException("OCR 语言选项编码失败", 400, false);
        }
    }
}
