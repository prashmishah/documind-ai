
package com.documind.backend.controller;

import com.documind.backend.model.Document;
import com.documind.backend.model.DocumentChunk;
import com.documind.backend.repository.DocumentRepository;
import com.documind.backend.repository.DocumentChunkRepository;
import com.documind.backend.service.AiServiceClient;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentRepository repository;
    private final DocumentChunkRepository chunkRepository;
    private final AiServiceClient aiServiceClient;

    private final JsonMapper mapper = new JsonMapper();

    private final Path uploadDir =
            Paths.get("uploads").toAbsolutePath();

    public DocumentController(
            DocumentRepository repository,
            AiServiceClient aiServiceClient,
            DocumentChunkRepository chunkRepository) {

        this.repository = repository;
        this.aiServiceClient = aiServiceClient;
        this.chunkRepository = chunkRepository;
    }

    // ==========================================
    // UPLOAD PDF
    // ==========================================

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Document> upload(
            @RequestParam("file") MultipartFile file)
            throws IOException {

        if (file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File is empty"
            );
        }

        String originalName = file.getOriginalFilename();

        if (originalName == null ||
                !originalName.toLowerCase().endsWith(".pdf")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Only PDF files are allowed"
            );
        }

        byte[] signature;

        try (var input = file.getInputStream()) {
            signature = input.readNBytes(5);
        }

        if (signature.length != 5 ||
                !Arrays.equals(
                        signature,
                        new byte[]{'%', 'P', 'D', 'F', '-'})) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid PDF file"
            );
        }

        Files.createDirectories(uploadDir);

        String storedName = UUID.randomUUID() + ".pdf";
        Path destination = uploadDir.resolve(storedName);

        try {
            file.transferTo(destination);

            Document document = new Document(
                    originalName,
                    "application/pdf",
                    file.getSize()
            );

            document.setStoredFilename(storedName);

            Document saved = repository.save(document);

            return ResponseEntity
                    .status(HttpStatus.CREATED)
                    .body(saved);

        } catch (Exception e) {
            Files.deleteIfExists(destination);
            throw e;
        }
    }

    // ==========================================
    // GET ALL DOCUMENTS
    // ==========================================

    @GetMapping
    public List<Document> getAll() {
        return repository.findAll();
    }

    // ==========================================
    // EXTRACT PDF + GENERATE EMBEDDINGS
    // ==========================================

    @PostMapping("/{id}/extract")
    public ResponseEntity<String> extractDocument(
            @PathVariable Long id) throws IOException {

        // Find uploaded document
        Document document = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Document not found"
                ));

        String storedFilename = document.getStoredFilename();

        if (storedFilename == null ||
                storedFilename.isBlank()) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This document has no stored PDF file"
            );
        }

        Path pdfPath = uploadDir
                .resolve(storedFilename)
                .normalize();

        if (!pdfPath.startsWith(uploadDir) ||
                !Files.isRegularFile(pdfPath)) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Stored PDF file not found"
            );
        }

        // STEP 1: Extract text using FastAPI
        String extractedText =
                aiServiceClient.extractText(pdfPath);

        // STEP 2: Parse JSON response
        JsonNode response = mapper.readTree(extractedText);

        JsonNode chunksNode = response.get("chunks");

        if (chunksNode == null || !chunksNode.isArray()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "FastAPI did not return a valid chunks array"
            );
        }

        // STEP 3: Extract text from chunk objects
        List<String> chunks = new ArrayList<>();

        for (JsonNode chunkNode : chunksNode) {

            JsonNode textNode = chunkNode.get("text");

            if (textNode == null || !textNode.isString()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Invalid chunk text returned by FastAPI"
                );
            }

            chunks.add(textNode.asString());
        }

        // Handle PDFs with no extractable text
        if (chunks.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "No extractable text found in PDF"
            );
        }

        // STEP 4: Generate embeddings
        List<float[]> embeddings =
                aiServiceClient.generateEmbeddings(chunks);

        if (embeddings.size() != chunks.size()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Embedding count does not match chunk count"
            );
        }

        // STEP 5: Prepare database records
        List<DocumentChunk> records = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {

            DocumentChunk chunk = new DocumentChunk();

            chunk.setDocument(document);
            chunk.setChunkIndex(i);
            chunk.setContent(chunks.get(i));
            chunk.setEmbedding(embeddings.get(i));

            records.add(chunk);
        }

        // STEP 6: Replace existing chunks
        List<DocumentChunk> existingChunks =
                chunkRepository
                        .findByDocumentIdOrderByChunkIndexAsc(id);

        chunkRepository.deleteAll(existingChunks);
        chunkRepository.flush();

        // STEP 7: Save new embeddings
        chunkRepository.saveAllAndFlush(records);

        // Return extracted PDF information
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(extractedText);
    }

    // ==========================================
    // DELETE DOCUMENT
    // ==========================================

    @DeleteMapping("/{id}")
    @org.springframework.transaction.annotation.Transactional
    public ResponseEntity<Void> deleteDocument(
            @PathVariable Long id) throws IOException {

        Document document = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Document not found"
                ));

        String storedFilename = document.getStoredFilename();

        // Delete associated chunks and embeddings first.
        List<DocumentChunk> chunks =
                chunkRepository.findByDocumentIdOrderByChunkIndexAsc(id);

        chunkRepository.deleteAll(chunks);
        chunkRepository.flush();

        // Delete the document database record.
        repository.delete(document);
        repository.flush();

        // Delete the uploaded PDF file if one exists.
        if (storedFilename != null && !storedFilename.isBlank()) {
            Path pdfPath = uploadDir.resolve(storedFilename).normalize();

            if (!pdfPath.startsWith(uploadDir)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Invalid stored PDF path"
                );
            }

            Files.deleteIfExists(pdfPath);
        }

        return ResponseEntity.noContent().build();
    }

}
