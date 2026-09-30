from fastapi import APIRouter, UploadFile, File, HTTPException

from app.schemas import CinBackExtractionResult, CinExtractionResult
from app.cin_extraction import CardNotDetectedError, extract_cin_back_fields, extract_cin_fields

router = APIRouter(prefix="/extract/cin", tags=["cin"])


@router.post("", response_model=CinExtractionResult)
async def extract_cin(image: UploadFile = File(...)) -> CinExtractionResult:
    """
    Front side of the Tunisian CIN: finds the card in the photo (works with the card held
    in the hand), then reads number, names, lineage and date/place of birth field by field.

    Returns 422 if no card outline could be found -- the frontend should treat that as a
    retake prompt.
    """
    image_bytes = await image.read()
    try:
        return extract_cin_fields(image_bytes)
    except CardNotDetectedError as e:
        raise HTTPException(status_code=422, detail=str(e))


@router.post("/back", response_model=CinBackExtractionResult)
async def extract_cin_back(image: UploadFile = File(...)) -> CinBackExtractionResult:
    """Back side of the CIN: profession, address and issue date. 422 = retake."""
    image_bytes = await image.read()
    try:
        return extract_cin_back_fields(image_bytes)
    except CardNotDetectedError as e:
        raise HTTPException(status_code=422, detail=str(e))
