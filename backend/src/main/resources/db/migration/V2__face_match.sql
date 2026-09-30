-- Selfie face match + liveness results, recorded on the document they were checked against.
-- (The frontal selfie frame itself is a document_image row with side = 'SELFIE', under the
-- same retention rules as the document photos.)
ALTER TABLE extracted_document ADD COLUMN face_similarity DOUBLE PRECISION;
ALTER TABLE extracted_document ADD COLUMN face_matched BOOLEAN;
ALTER TABLE extracted_document ADD COLUMN liveness_passed BOOLEAN;
ALTER TABLE extracted_document ADD COLUMN liveness_reason VARCHAR(200);
ALTER TABLE extracted_document ADD COLUMN selfie_checked_at TIMESTAMP(6) WITH TIME ZONE;
