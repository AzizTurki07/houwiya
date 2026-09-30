#!/usr/bin/env bash
# Runs once when the Codespace / dev container is created.
set -euo pipefail

echo "==> Tesseract (used by PassportEye and the CIN pipeline)"
sudo apt-get update
sudo apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-fra tesseract-ocr-ara libgl1

echo "==> AI service Python deps"
python -m pip install --upgrade pip
python -m pip install -r ai-service/requirements.txt

echo "==> Frontend deps"
(cd frontend && npm ci)

echo "==> Backend deps (warm the Maven cache)"
(cd backend && mvn -B -q dependency:go-offline || true)

cat <<'EOF'

Ready. Typical dev loop (one terminal each):
  docker compose up -d postgres ai-service      # db + AI service in Docker
  cd backend && mvn spring-boot:run             # API on :8080
  cd frontend && npm start                      # UI on :4200 (open the forwarded 4200 URL)
EOF
