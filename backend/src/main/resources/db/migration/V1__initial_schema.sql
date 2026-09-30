-- Initial schema. Written to run on PostgreSQL (dev/prod) and on H2 in PostgreSQL mode (tests).
--
-- Columns holding personal data are encrypted by the application (AES-256-GCM, see
-- FieldEncryptor) and therefore stored as TEXT: document_number, date_of_birth,
-- extracted_fields_json, original_fields_json. document_number_hash is a keyed HMAC of the
-- number, so duplicate applications can be detected without decrypting anything.
--
-- Document photos (document_image) are kept encrypted only while a human may still need them:
-- deleted when the document is auto-approved or an admin decides, when the user deletes the
-- session, and in any case after app.retention.image-days.

CREATE TABLE app_user (
    id            UUID PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    created_at    TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE onboarding_session (
    id                UUID PRIMARY KEY,
    user_id           UUID NOT NULL REFERENCES app_user (id),
    status            VARCHAR(30) NOT NULL,
    consent_given     BOOLEAN NOT NULL,
    consent_timestamp TIMESTAMP(6) WITH TIME ZONE,
    created_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_session_user ON onboarding_session (user_id);

CREATE TABLE extracted_document (
    id                    UUID PRIMARY KEY,
    session_id            UUID NOT NULL UNIQUE REFERENCES onboarding_session (id),
    document_type         VARCHAR(20) NOT NULL,
    document_number       TEXT,
    document_number_hash  VARCHAR(64),
    date_of_birth         TEXT,
    expiry_date           DATE,
    extracted_fields_json TEXT,
    original_fields_json  TEXT,
    ocr_confidence        DOUBLE PRECISION,
    checksum_valid        BOOLEAN,
    user_corrected        BOOLEAN NOT NULL DEFAULT FALSE,
    back_side_captured    BOOLEAN NOT NULL DEFAULT FALSE,
    review_status         VARCHAR(20) NOT NULL,
    confirmed_at          TIMESTAMP(6) WITH TIME ZONE,
    reviewed_by           VARCHAR(255),
    reviewed_at           TIMESTAMP(6) WITH TIME ZONE,
    decision_reason       TEXT,
    created_at            TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_document_number_hash ON extracted_document (document_number_hash);
CREATE INDEX idx_document_review_queue ON extracted_document (review_status, confirmed_at);

CREATE TABLE document_image (
    id           UUID PRIMARY KEY,
    document_id  UUID NOT NULL REFERENCES extracted_document (id) ON DELETE CASCADE,
    side         VARCHAR(10) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    data         BYTEA NOT NULL,  -- AES-256-GCM encrypted image bytes
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    UNIQUE (document_id, side)
);

-- Who did what, when. Never holds document values -- only ids and short, non-personal details.
CREATE TABLE audit_event (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    actor       VARCHAR(255),
    action      VARCHAR(50) NOT NULL,
    target_type VARCHAR(50),
    target_id   UUID,
    details     VARCHAR(500)
);

CREATE INDEX idx_audit_occurred_at ON audit_event (occurred_at);
CREATE INDEX idx_audit_target ON audit_event (target_id);
