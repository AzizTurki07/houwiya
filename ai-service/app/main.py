from fastapi import FastAPI

from app.routers import passport, cin

app = FastAPI(
    title="ID Onboarding AI Service",
    description="OCR/extraction microservice for Tunisian CIN and passport documents.",
    version="0.1.0",
)

app.include_router(passport.router)
app.include_router(cin.router)


@app.get("/health")
async def health():
    return {"status": "ok"}
