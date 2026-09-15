# ID/Passport Onboarding Platform

Scans Tunisian CIN and passport documents during onboarding and extracts data via
an AI/OCR microservice. See the project roadmap for the full phase-by-phase plan.

## Layout

```
backend/       Spring Boot API (auth, onboarding sessions, persistence)
ai-service/    Python FastAPI microservice (OCR/extraction - currently stubbed)
frontend/      Angular app (capture UI, review screen, admin dashboard)
docker-compose.yml
```

## Current status (end of Phase 3)

- Backend: entities (`User`, `OnboardingSession`, `ExtractedDocument`), JWT auth
  (`/api/auth/register`, `/api/auth/login`), session lifecycle endpoints
  (`/api/onboarding/sessions`) with consent tracking, plus a context-load test
  and a real auth integration test (register/login/duplicate-email/wrong-password)
  against in-memory H2. It does NOT yet call the AI service to persist
  extraction results -- that wiring is Phase 5.
- AI service: FastAPI with **both** real extraction pipelines.
  - `/extract/passport` uses PassportEye (locates + crops + OCRs the MRZ),
    returns ISO dates and a `checksum_valid` flag from the ICAO check digits,
    and responds `422` if no MRZ is found.
  - `/extract/cin` detects and perspective-corrects the card with OpenCV, then
    OCRs six pre-defined field regions independently (Tesseract, French by
    default -- see the docstring in `cin_extraction.py` for why bilingual
    `fra+ara` needs testing against real cards), validates the document number
    format and date parsing, and responds `422` if no card outline is found.
  - Both pipelines verified against synthetic test fixtures in this sandbox --
    `pytest tests/ -v` passes all 4 tests (2 passport, 2 CIN).
- Frontend: Angular 18 app scaffolded, `AuthService` + JWT interceptor wired up
  against the backend. No screens built yet - that starts around Phase 5/6.
  A full UI/UX mockup (`houwiya-ui-mockup.html`, shared separately) covers the
  intended screens for reference while building.

### Running the AI service tests
```bash
cd ai-service
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
# tesseract-ocr must also be installed as a system package, e.g.:
#   apt install tesseract-ocr   (Linux)
#   brew install tesseract      (Mac)
pytest tests/ -v
```

## Running everything locally

### Option A: Docker Compose (backend + ai-service + db)
```bash
docker compose up --build
```
- Backend: http://localhost:8080 (Swagger UI at `/swagger-ui.html`)
- AI service: http://localhost:8000 (interactive docs at `/docs`)
- Postgres: localhost:5432 (db `onboarding` / user `onboarding` / pass `onboarding`)

### Option B: Run services individually (better for active development)

**Backend** (needs your own Maven/JDK 21 setup - this sandbox couldn't reach Maven
Central to verify the build, so do a first `mvn clean install` locally to confirm):
```bash
cd backend
mvn spring-boot:run
```

**AI service:**
```bash
cd ai-service
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8000
```

**Frontend:**
```bash
cd frontend
npm install   # already done once in this scaffold, re-run if you re-clone
npm start     # serves on http://localhost:4200
```

## Before you write any real code

1. Set a real `JWT_SECRET` (don't commit it - use an env var or `.env` file).
2. Decide your document-image retention policy (see `rawImagePath` note in
   `ExtractedDocument.java`) before you start persisting real captured images.
3. Only use your own ID / consenting family members' documents for test data -
   never scrape or reuse other people's real ID photos.

## Continuous Integration

Three independent GitHub Actions workflows live in `.github/workflows/`, one
per service, each only triggering when that service's folder changes:

| Workflow | What it does |
|---|---|
| `backend-ci.yml` | JDK 21 + Maven, runs `mvn test` against an in-memory H2 database (no Postgres needed in CI), then builds the jar |
| `ai-service-ci.yml` | Installs Tesseract + French/Arabic language packs via apt, installs the pip requirements, runs `pytest tests/ -v` (both the passport MRZ and CIN tests) |
| `frontend-ci.yml` | `npm ci`, `ng build`, then runs the Karma unit tests headless (`ubuntu-latest` runners ship Chrome pre-installed, which `karma-chrome-launcher` picks up automatically) |

**To get this running:**
```bash
cd id-onboarding-platform
git init
git add .
git commit -m "Initial scaffold: backend, ai-service, frontend, CI"
git branch -M main
git remote add origin https://github.com/<your-username>/<your-repo>.git
git push -u origin main
```
Once pushed, check the **Actions** tab on GitHub — all three workflows should
run automatically. Add badges to the top of this README once you know your
repo path:
```md
![Backend CI](https://github.com/<your-username>/<your-repo>/actions/workflows/backend-ci.yml/badge.svg)
![AI Service CI](https://github.com/<your-username>/<your-repo>/actions/workflows/ai-service-ci.yml/badge.svg)
![Frontend CI](https://github.com/<your-username>/<your-repo>/actions/workflows/frontend-ci.yml/badge.svg)
```

**What's actually verified vs. what to double check:**
- `ai-service-ci.yml` steps were run and passed in this build's sandbox exactly as written (apt install + pip install + pytest) — high confidence this works as-is.
- `backend-ci.yml` could NOT be run here (no Maven Central network access in this sandbox) — the tests themselves (`PlatformApplicationTests`, `AuthControllerTest`) are new, so run `mvn test` locally at least once before you trust the CI green check.
- `frontend-ci.yml`'s build step was verified locally; the Karma/ChromeHeadless test step could not be, since this sandbox has no Chrome/Chromium available to install. This is a very standard pattern on GitHub's `ubuntu-latest` runners, but keep an eye on the first run.

## Next steps (Phase 4/5 of the roadmap)

Wire the backend to actually call the AI service: on document upload, the
Spring Boot `OnboardingController` should call `/extract/passport` or
`/extract/cin`, persist the result into `ExtractedDocument`, and move the
session into `PENDING_REVIEW`. That's also when the duplicate-document-number
and expiry checks discussed earlier fit in.
