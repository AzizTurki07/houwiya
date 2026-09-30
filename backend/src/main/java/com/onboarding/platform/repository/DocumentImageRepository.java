package com.onboarding.platform.repository;

import com.onboarding.platform.entity.DocumentImage;
import com.onboarding.platform.enums.DocumentSide;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentImageRepository extends JpaRepository<DocumentImage, UUID> {

    Optional<DocumentImage> findByDocumentIdAndSide(UUID documentId, DocumentSide side);

    // Metadata only (no image bytes) for listing which sides are available.
    @Query("select i.side from DocumentImage i where i.documentId = :documentId")
    List<DocumentSide> findSidesByDocumentId(UUID documentId);

    @Modifying
    @Query("delete from DocumentImage i where i.documentId = :documentId")
    int deleteByDocumentId(UUID documentId);

    @Modifying
    @Query("delete from DocumentImage i where i.documentId = :documentId and i.side = :side")
    int deleteByDocumentIdAndSide(UUID documentId, DocumentSide side);

    @Modifying
    @Query("delete from DocumentImage i where i.createdAt < :cutoff")
    int deleteOlderThan(Instant cutoff);
}
