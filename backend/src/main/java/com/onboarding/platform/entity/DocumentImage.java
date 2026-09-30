package com.onboarding.platform.entity;

import com.onboarding.platform.enums.DocumentSide;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A document photo, encrypted, kept only while a reviewer may still need it (see
 * DocumentImageService for the retention rules). Deleted with its document (ON DELETE CASCADE).
 */
@Entity
@Table(name = "document_image")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentImage {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DocumentSide side;

    @Column(nullable = false, length = 100)
    private String contentType;

    // AES-256-GCM ciphertext of the image bytes (FieldEncryptor.encryptBytes).
    @Column(nullable = false, columnDefinition = "BYTEA")
    private byte[] data;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
