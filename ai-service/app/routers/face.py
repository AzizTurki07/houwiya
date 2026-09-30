from typing import List

from fastapi import APIRouter, File, HTTPException, UploadFile

from app.face import MATCH_THRESHOLD, verify
from app.schemas import FaceVerificationResult

router = APIRouter(prefix="/face", tags=["face"])


@router.post("/verify", response_model=FaceVerificationResult)
async def verify_face(document: UploadFile = File(...), selfies: List[UploadFile] = File(...)) -> FaceVerificationResult:
    """
    Compares the portrait on the document photo with the first (frontal) selfie frame, and
    checks liveness: frame 1 looks straight at the camera, the next frames turn the head one
    way and the other, all showing the same single person.

    Missing faces are reported in the result (not as an error) so the backend can record
    them; 422 only for images that can't be decoded.
    """
    try:
        result = verify(await document.read(), [await s.read() for s in selfies])
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
    return FaceVerificationResult(
        document_face_found=result.document_face_found,
        selfie_face_found=result.selfie_face_found,
        similarity=result.similarity,
        match=result.match,
        threshold=MATCH_THRESHOLD,
        liveness_passed=result.liveness.passed,
        liveness_reason=result.liveness.reason,
    )
