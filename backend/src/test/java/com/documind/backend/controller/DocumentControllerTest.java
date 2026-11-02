
package com.documind.backend.controller;

import com.documind.backend.model.Document;
import com.documind.backend.repository.DocumentRepository;
import com.documind.backend.repository.DocumentChunkRepository;
import com.documind.backend.service.AiServiceClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DocumentControllerTest {

    private DocumentRepository documentRepository;
    private DocumentController controller;

    @TempDir
    Path temporaryDirectory;

    @BeforeEach
    void setUp() throws Exception {

        documentRepository = mock(DocumentRepository.class);

        DocumentChunkRepository chunkRepository =
                mock(DocumentChunkRepository.class);

        AiServiceClient aiServiceClient =
                mock(AiServiceClient.class);

        controller = new DocumentController(
                documentRepository,
                aiServiceClient,
                chunkRepository
        );

        Field uploadDirField =
                DocumentController.class.getDeclaredField("uploadDir");

        uploadDirField.setAccessible(true);
        uploadDirField.set(controller, temporaryDirectory);
    }

    @Test
    void emptyFileShouldBeRejected() {

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "empty.pdf",
                "application/pdf",
                new byte[0]
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.upload(file)
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verifyNoInteractions(documentRepository);
    }

    @Test
    void nonPdfFileShouldBeRejected() {

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "Hello World".getBytes()
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.upload(file)
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verifyNoInteractions(documentRepository);
    }

    @Test
    void fakePdfShouldBeRejected() {

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "fake.pdf",
                "application/pdf",
                "This is not a PDF".getBytes()
        );

        ResponseStatusException exception =
                assertThrows(
                        ResponseStatusException.class,
                        () -> controller.upload(file)
                );

        assertEquals(
                HttpStatus.BAD_REQUEST,
                exception.getStatusCode()
        );

        verifyNoInteractions(documentRepository);
    }

    @Test
    void validPdfShouldBeUploaded() throws Exception {

        byte[] pdfContent =
                "%PDF-1.4\nTest PDF content".getBytes();

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "sample.pdf",
                "application/pdf",
                pdfContent
        );

        when(documentRepository.save(any(Document.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        ResponseEntity<Document> response =
                controller.upload(file);

        assertEquals(
                HttpStatus.CREATED,
                response.getStatusCode()
        );

        assertNotNull(response.getBody());

        assertEquals(
                "sample.pdf",
                response.getBody().getFilename()
        );

        String storedFilename =
                response.getBody().getStoredFilename();

        assertNotNull(storedFilename);

        assertTrue(
                Files.exists(
                        temporaryDirectory.resolve(storedFilename)
                )
        );

        verify(documentRepository, times(1))
                .save(any(Document.class));
    }
}
