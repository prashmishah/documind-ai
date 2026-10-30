
package com.documind.backend.repository;

import com.documind.backend.model.DocumentChunk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository
        extends JpaRepository<DocumentChunk, Long> {

    // Retrieve chunks in their original order
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(
            Long documentId
    );

    // Search for chunks similar to a question embedding
    @Query(
        value = """
            SELECT *
            FROM document_chunks
            WHERE document_id = :documentId
            ORDER BY embedding <=> CAST(:queryVector AS vector)
            LIMIT 5
            """,
        nativeQuery = true
    )
    List<DocumentChunk> findSimilarChunks(
            @Param("documentId") Long documentId,
            @Param("queryVector") String queryVector
    );
}
