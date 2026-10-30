
package com.documind.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiServiceClient {

    private final HttpClient httpClient;
    private final String aiServiceUrl;
    private final JsonMapper mapper = new JsonMapper();

    public AiServiceClient(
            @Value("${ai.service.url:http://127.0.0.1:8000}")
            String aiServiceUrl) {

        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.aiServiceUrl = aiServiceUrl;
    }

    // ==========================================
    // EXTRACT PDF TEXT
    // ==========================================

    public String extractText(Path pdfPath) throws IOException {

        if (!Files.isRegularFile(pdfPath)) {
            throw new IOException(
                    "PDF not found: " + pdfPath
            );
        }

        String boundary =
                "DocuMindBoundary" + UUID.randomUUID();

        String header =
                "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; " +
                "name=\"file\"; filename=\"document.pdf\"\r\n" +
                "Content-Type: application/pdf\r\n" +
                "\r\n";

        String footer =
                "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes =
                header.getBytes(StandardCharsets.UTF_8);

        byte[] pdfBytes =
                Files.readAllBytes(pdfPath);

        byte[] footerBytes =
                footer.getBytes(StandardCharsets.UTF_8);

        byte[] requestBody = new byte[
                headerBytes.length +
                pdfBytes.length +
                footerBytes.length
        ];

        System.arraycopy(
                headerBytes, 0,
                requestBody, 0,
                headerBytes.length
        );

        System.arraycopy(
                pdfBytes, 0,
                requestBody, headerBytes.length,
                pdfBytes.length
        );

        System.arraycopy(
                footerBytes, 0,
                requestBody,
                headerBytes.length + pdfBytes.length,
                footerBytes.length
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(aiServiceUrl + "/extract-text"))
                .header(
                        "Content-Type",
                        "multipart/form-data; boundary=" + boundary
                )
                .POST(
                        HttpRequest.BodyPublishers
                                .ofByteArray(requestBody)
                )
                .build();

        return sendRequest(request);
    }

    // ==========================================
    // GENERATE AI EMBEDDINGS
    // ==========================================

    public List<float[]> generateEmbeddings(
            List<String> chunks) throws IOException {

        if (chunks == null || chunks.isEmpty()) {
            throw new IOException(
                    "Cannot generate embeddings for empty chunks"
            );
        }

        // FastAPI expects the JSON key "texts"
        String jsonBody = mapper.writeValueAsString(
                Map.of("texts", chunks)
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(
                        URI.create(
                                aiServiceUrl + "/generate-embeddings"
                        )
                )
                .header(
                        "Content-Type",
                        "application/json"
                )
                .POST(
                        HttpRequest.BodyPublishers
                                .ofString(jsonBody)
                )
                .build();

        String responseBody = sendRequest(request);

        JsonNode response = mapper.readTree(responseBody);

        JsonNode embeddingsNode =
                response.get("embeddings");

        if (embeddingsNode == null ||
                !embeddingsNode.isArray()) {

            throw new IOException(
                    "FastAPI did not return an embeddings array"
            );
        }

        List<float[]> embeddings = new ArrayList<>();

        for (JsonNode vectorNode : embeddingsNode) {

            if (!vectorNode.isArray() ||
                    vectorNode.size() != 384) {

                throw new IOException(
                        "Expected a 384-dimensional embedding"
                );
            }

            float[] vector = new float[384];

            for (int i = 0; i < 384; i++) {
                vector[i] = (float)
                        vectorNode.get(i).asDouble();
            }

            embeddings.add(vector);
        }

        if (embeddings.size() != chunks.size()) {
            throw new IOException(
                    "Embedding count does not match chunk count"
            );
        }

        return embeddings;
    }

    // ==========================================
    // SHARED HTTP REQUEST HANDLER
    // ==========================================

    private String sendRequest(
            HttpRequest request) throws IOException {

        try {

            HttpResponse<String> response =
                    httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString()
                    );

            if (response.statusCode() != 200) {
                throw new IOException(
                        "FastAPI returned HTTP " +
                        response.statusCode() +
                        ": " +
                        response.body()
                );
            }

            return response.body();

        } catch (InterruptedException e) {

            Thread.currentThread().interrupt();

            throw new IOException(
                    "Request to FastAPI was interrupted",
                    e
            );
        }
    }
}
