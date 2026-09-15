from fastapi import APIRouter, UploadFile, File, HTTPException

from app.schemas import PassportExtractionResult
from app.mrz_extraction import extract_passport_mrz, MrzNotFoundError

router = APIRouter(prefix="/extract/passport", tags=["passport"])


@router.post("", response_model=PassportExtractionResult)
async def extract_passport(image: UploadFile = File(...)) -> PassportExtractionResult:
    """
    Reads the passport bio page, locates the MRZ (PassportEye handles crop +
    OCR internally), parses the fields, and validates every ICAO check digit.

    Returns 422 if no MRZ could be found -- the frontend should treat that as
    a retake prompt, not a generic error.
    """
    image_bytes = await image.read()

    try:
        return extract_passport_mrz(image_bytes)
    except MrzNotFoundError as e:
        raise HTTPException(status_code=422, detail=str(e))
