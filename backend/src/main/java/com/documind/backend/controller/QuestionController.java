
package com.documind.backend.controller;

import com.documind.backend.model.DocumentChunk;
import com.documind.backend.repository.DocumentChunkRepository;
import com.documind.backend.repository.DocumentRepository;
import com.documind.backend.service.AiServiceClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class QuestionController {

    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository chunkRepository;
    private final AiServiceClient aiServiceClient;
    private final String aiServiceUrl;

    private final JsonMapper mapper = new JsonMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public QuestionController(
            DocumentRepository documentRepository,
            DocumentChunkRepository chunkRepository,
            AiServiceClient aiServiceClient,
            @Value("${ai.service.url:http://127.0.0.1:8000}")
            String aiServiceUrl) {

        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.aiServiceClient = aiServiceClient;
        this.aiServiceUrl = aiServiceUrl.replaceAll("/+$", "");
    }

    public record QuestionRequest(String question) {}

    @PostMapping("/{documentId}/ask")
    public Map<String, Object> askQuestion(
            @PathVariable Long documentId,
            @RequestBody QuestionRequest request) {

        if (request == null ||
                request.question() == null ||
                request.question().isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Question cannot be empty"
            );
        }

        if (!documentRepository.existsById(documentId)) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Document not found"
            );
        }

        try {
            // STEP 1: Generate question embedding
            List<float[]> embeddings =
                    aiServiceClient.generateEmbeddings(
                            List.of(request.question())
                    );

            if (embeddings.isEmpty()) {
                throw new IOException(
                        "Embedding service returned no vectors"
                );
            }

            // STEP 2: Convert embedding to pgvector format
            float[] vector = embeddings.get(0);

            StringBuilder vectorText = new StringBuilder("[");

            for (int i = 0; i < vector.length; i++) {
                if (i > 0) {
                    vectorText.append(",");
                }
                vectorText.append(vector[i]);
            }

            vectorText.append("]");

            // STEP 3: Retrieve relevant document chunks
            List<DocumentChunk> relevantChunks =
                    chunkRepository.findSimilarChunks(
                            documentId,
                            vectorText.toString()
                    );

            if (relevantChunks.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Document has no processed text. Extract it first."
                );
            }

            // STEP 4: Build context
            StringBuilder context = new StringBuilder();

            for (DocumentChunk chunk : relevantChunks) {
                context.append(chunk.getContent());
                context.append("\n\n");
            }

            // STEP 5: Create JSON request for FastAPI
            String jsonBody = mapper.writeValueAsString(
                    Map.of(
                            "question", request.question(),
                            "context", context.toString()
                    )
            );

            // STEP 6: Send HTTP/1.1 request to FastAPI
            HttpRequest aiRequest = HttpRequest.newBuilder()
                    .uri(URI.create(
                            aiServiceUrl + "/ask-question"
                    ))
                    .timeout(Duration.ofSeconds(240))
                    .header(
                            "Content-Type",
                            "application/json; charset=utf-8"
                    )
                    .header("Accept", "application/json")
                    .version(HttpClient.Version.HTTP_1_1)
                    .POST(
                            HttpRequest.BodyPublishers.ofString(
                                    jsonBody,
                                    StandardCharsets.UTF_8
                            )
                    )
                    .build();

            HttpResponse<String> aiResponse =
                    httpClient.send(
                            aiRequest,
                            HttpResponse.BodyHandlers.ofString(
                                    StandardCharsets.UTF_8
                            )
                    );

            // STEP 7: Handle FastAPI errors
            if (aiResponse.statusCode() != 200) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "FastAPI HTTP " +
                                aiResponse.statusCode() +
                                ": " +
                                aiResponse.body()
                );
            }

            // STEP 8: Parse generated answer
            JsonNode result = mapper.readTree(
                    aiResponse.body()
            );

            JsonNode answerNode = result.get("answer");

            if (answerNode == null ||
                    !answerNode.isString()) {

                throw new IOException(
                        "FastAPI response did not contain an answer string"
                );
            }

            // STEP 9: Return final result
            return Map.of(
                    "documentId", documentId,
                    "question", request.question(),
                    "answer", answerNode.asString(),
                    "model", "qwen2.5:3b",
                    "chunksUsed", relevantChunks.size()
            );

        } catch (ResponseStatusException e) {
            throw e;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI request interrupted",
                    e
            );

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI service request failed: " + e.getMessage(),
                    e
            );
        }
    }
}
