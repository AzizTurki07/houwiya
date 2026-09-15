from fastapi import APIRouter, UploadFile, File, HTTPException

from app.schemas import CinExtractionResult
from app.cin_extraction import extract_cin_fields, CardNotDetectedError

router = APIRouter(prefix="/extract/cin", tags=["cin"])


@router.post("", response_model=CinExtractionResult)
async def extract_cin(image: UploadFile = File(...)) -> CinExtractionResult:
    """
    Detects and crops the card (OpenCV perspective correction), then OCRs
    each pre-defined field region independently -- there's no MRZ on the
    Tunisian CIN to lean on, so this is a region/template-based approach.

    Returns 422 if no card outline could be found -- the frontend should
    treat that as a retake prompt.
    """
    image_bytes = await image.read()

    try:
        return extract_cin_fields(image_bytes)
    except CardNotDetectedError as e:
        raise HTTPException(status_code=422, detail=str(e))
