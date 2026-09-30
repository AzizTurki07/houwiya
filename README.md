# ID/Passport Onboarding Platform

Scans Tunisian CIN and passport documents during onboarding and extracts data via
an AI/OCR microservice. See the project roadmap for the full phase-by-phase plan.

## Layout

```
backend/       Spring Boot API (auth, onboarding sessions, persistence)
ai-service/    Python FastAPI microservice (passport MRZ + Tunisian CIN OCR)
frontend/      Angular app (capture UI, review screen, admin dashboard)
docker-compose.yml
```

## Current status (Phases 2-5; Phase 4 accuracy work ongoing)

- Backend: entities (`User`, `OnboardingSession`, `ExtractedDocument`), JWT auth
  (`/api/auth/register`, `/api/auth/login`), and the full onboarding flow wired to the
  AI service over `WebClient`:

  | Endpoint | What it does |
  |---|---|
  | `POST /api/onboarding/sessions` | create a session (`STARTED`) |
  | `POST /api/onboarding/sessions/{id}/consent` | record consent (`CONSENT_GIVEN`) |
  | `POST /api/onboarding/sessions/{id}/document` | multipart `file` + `documentType` (`PASSPORT`/`CIN`) + optional `side` (`FRONT` default, `BACK` for the CIN) -> calls the matching `/extract/...` endpoint, persists the result, session -> `PENDING_REVIEW`. The CIN back is merged into the same document; re-upload before confirming = retake of that side |
  | `GET  /api/onboarding/sessions/{id}/document` | extracted fields + `fieldConfidence`, `ocrConfidence`, `checksumValid`, `warnings`, `backSideRequired`/`backSideCaptured` for the review screen |
  | `POST /api/onboarding/sessions/{id}/confirm` | `{"fields": {...}}` with the user's reviewed values -> auto-approved if clean, otherwise `NEEDS_REVIEW` for an admin. A CIN needs both sides first (`409` otherwise) |

  Warnings that block auto-approval: `LOW_CONFIDENCE` (overall **or any single
  field** below `app.review.min-confidence`, default 0.7), `CHECKSUM_FAILED`, `MISSING_REQUIRED_FIELDS`, `DOCUMENT_EXPIRED`,
  `USER_CORRECTED`. A document number already used by another application is rejected
  with `409`. Error codes: `422` unreadable image (prompt a retake), `502` AI service
  down/timeout, `404` session not found or not yours, `409` wrong session state.
  Covered by `OnboardingDocumentFlowTest` (AI client mocked) plus the auth tests.
  > If your local Postgres already has an `extracted_document` table from an earlier
  > run, drop it (or `docker compose down -v`): `extracted_fields_json` changed from
  > `oid` to `text`, which `ddl-auto: update` won't migrate.
- AI service: FastAPI with both pipelines, evaluated on real (consented) documents --
  see [ai-service/eval/REPORT.md](ai-service/eval/REPORT.md) and
  [ai-service/eval/README.md](ai-service/eval/README.md) for numbers and findings.
  - `/extract/passport`: PassportEye locates + OCRs the MRZ; ISO dates, `checksum_valid`
    from the ICAO check digits, `422` if no MRZ is found.
  - `/extract/cin` (front) and `/extract/cin/back`: the real card is Arabic-only. The card
    is found in hand-held photos (fingers over corners, card cut by the frame --
    `card_detection.py`), warped to a canonical size, and each field is read from its own
    row band after an "ink filter" keeps only the black value text (the purple labels,
    background art and stamp are dropped). Dates with Arabic month names
    ("02 اكتوبر 2001") are parsed, digits are re-read with a digits-only model, an
    upside-down photo is retried rotated. Front: number, surname, first name, lineage,
    date/place of birth. Back: profession, address, issue date (the mother's name is
    deliberately not extracted). `422` if no card is found.
  - Every result carries a `field_confidence` map (0-1 per field); a field that fails its
    own validation (check digit, 8-digit number, date) is capped at 0.3.
  - Tests (39): synthetic CIN front/back fixtures in the real layout
    (`tests/generate_cin_fixture.py`, fictitious data), card detection with a thumb over a
    corner / card off the frame, Arabic date parsing, passport per-field rules and the
    expiry-century regression.
- Frontend: Angular 18, full end-to-end flow (lazy-loaded standalone components):
  sign in / register -> my verifications -> consent -> choose passport or CIN ->
  live camera with a document guide (or upload a photo) -> upload progress + "reading
  your document" state -> **editable review screen** (reading-quality meter, fields
  highlighted from the backend `warnings`, a per-field confidence marker with any
  individually hard-to-read field flagged, per-field validation such as 8-digit CIN
  numbers) -> confirm -> verified / submitted-for-review result. A `422` from the AI
  service shows a retake prompt with tips; a `502` offers to retry the same photo;
  an expired JWT signs the user out and returns them to login.
  The API is called through the relative path `/api`, which `ng serve` proxies to
  `localhost:8080` (`frontend/proxy.conf.json`), so the same build works locally and
  behind a Codespaces forwarded URL.

### Running the AI service tests
OCR results depend on the exact Tesseract build, so tests run inside the service image
(this is also what CI does):
```bash
docker build -t houwiya-ai ai-service
docker run --rm houwiya-ai python -m pytest tests/ -v
```

## Running in GitHub Codespaces

The repo has a dev container (`.devcontainer/`) with JDK 21 + Maven, Node 20,
Python 3.12, Tesseract (fra/ara) and Docker-in-Docker; dependencies install on
creation. Pick a **4-core** machine (Maven + OCR + Angular together need the RAM).
Then, one terminal each:

```bash
docker compose up -d postgres ai-service
```
```bash
cd backend && mvn spring-boot:run
```
```bash
cd frontend && npm start
```

Open the forwarded **port 4200** URL (Ports tab). Only 4200 needs to be reachable:
the Angular dev server proxies `/api` to the backend inside the Codespace. The
camera works there because forwarded URLs are HTTPS; you can also open the same URL
on your phone (set the port to *Public* first, or sign in to GitHub on the phone)
to test the live capture with a real rear camera.

Backend CORS allows `http://localhost:4200` and `https://*.app.github.dev` by
default; override with `CORS_ALLOWED_ORIGINS` (comma-separated patterns).

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
npm install
npm start     # serves on http://localhost:4200, proxies /api -> localhost:8080
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
| `ai-service-ci.yml` | Builds the production AI service image (Tesseract + fra/ara, layer-cached) and runs `pytest` inside it, so CI tests exactly what ships |
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

**Verified locally:** backend `mvn test` (18 tests, H2), frontend build + Karma headless
(40 tests), and the AI service tests run inside the freshly built image exactly as the
workflow does (39 tests). The Buildx layer cache (`type=gha`) only exists on GitHub, so the
first CI run of the AI workflow builds the image from scratch (a few minutes).

## Next steps

- Grow the CIN evaluation set to 10-20 consented cards (`ai-service/eval/README.md`) and
  re-run the Tesseract vs PaddleOCR comparison on it; current numbers come from one card.
- Reduce "confident but wrong" reads (Tesseract's confidence is poorly calibrated on
  Arabic names), e.g. by cross-checking the CIN number against the back's barcode.
- Admin review queue for `NEEDS_REVIEW` documents.
