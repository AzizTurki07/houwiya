from typing import Dict, Optional
from pydantic import BaseModel, Field


# Per-field confidence (0.0 - 1.0), keyed by the same names as the value fields,
# e.g. {"document_number": 0.95, "surname": 0.4}. Lets the review screen point at the
# specific fields that need a second look instead of only flagging the whole document.
FieldConfidence = Dict[str, float]


class PassportExtractionResult(BaseModel):
    """Shape returned by /extract/passport."""
    document_number: Optional[str] = None
    surname: Optional[str] = None
    given_names: Optional[str] = None
    nationality: Optional[str] = None
    date_of_birth: Optional[str] = None  # ISO format, e.g. 1998-04-12
    sex: Optional[str] = None
    expiry_date: Optional[str] = None
    checksum_valid: Optional[bool] = None
    overall_confidence: float = 0.0
    field_confidence: FieldConfidence = Field(default_factory=dict)


class CinExtractionResult(BaseModel):
    """Shape returned by /extract/cin (front side of the card). Values are in Arabic."""
    document_number: Optional[str] = None
    last_name: Optional[str] = None       # اللقب
    first_name: Optional[str] = None      # الاسم
    lineage: Optional[str] = None         # "بن/بنت ..." line (father's and grandfather's names)
    date_of_birth: Optional[str] = None   # ISO, parsed from e.g. "02 اكتوبر 2001"
    place_of_birth: Optional[str] = None  # مكانها
    overall_confidence: float = 0.0
    field_confidence: FieldConfidence = Field(default_factory=dict)


class CinBackExtractionResult(BaseModel):
    """Shape returned by /extract/cin/back. The mother's name printed there is not extracted."""
    profession: Optional[str] = None      # المهنة
    address: Optional[str] = None         # العنوان (both lines, joined)
    issue_date: Optional[str] = None      # ISO, from "تونس في 26 سبتمبر 2019"
    overall_confidence: float = 0.0
    field_confidence: FieldConfidence = Field(default_factory=dict)


class FaceVerificationResult(BaseModel):
    """Shape returned by /face/verify."""
    document_face_found: bool
    selfie_face_found: bool
    similarity: Optional[float] = None   # cosine similarity, document portrait vs frontal selfie
    match: Optional[bool] = None         # None when either face is missing
    threshold: float
    liveness_passed: bool
    liveness_reason: Optional[str] = None
