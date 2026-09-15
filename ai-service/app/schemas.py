from typing import Optional
from pydantic import BaseModel, Field


class ExtractedField(BaseModel):
    value: Optional[str] = None
    confidence: float = Field(ge=0.0, le=1.0)


class PassportExtractionResult(BaseModel):
    """Shape returned by /extract/passport once Phase 2 (MRZ pipeline) is implemented."""
    document_number: Optional[str] = None
    surname: Optional[str] = None
    given_names: Optional[str] = None
    nationality: Optional[str] = None
    date_of_birth: Optional[str] = None  # ISO format, e.g. 1998-04-12
    sex: Optional[str] = None
    expiry_date: Optional[str] = None
    checksum_valid: Optional[bool] = None
    overall_confidence: float = 0.0


class CinExtractionResult(BaseModel):
    """Shape returned by /extract/cin once Phase 3 (CIN pipeline) is implemented."""
    document_number: Optional[str] = None
    last_name: Optional[str] = None
    first_name: Optional[str] = None
    date_of_birth: Optional[str] = None
    place_of_birth: Optional[str] = None
    address: Optional[str] = None
    overall_confidence: float = 0.0
