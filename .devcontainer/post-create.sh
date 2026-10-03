#!/usr/bin/env bash
# Runs once when the Codespace / dev container is created.
set -euo pipefail

echo "==> Tesseract (used by PassportEye and the CIN pipeline)"
sudo apt-get update
sudo apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-fra tesseract-ocr-ara libgl1

echo "==> AI service Python deps"
python -m pip install --upgrade pip
python -m pip install -r ai-service/requirements.txt

echo "==> Face match models (only needed when running the AI service outside Docker)"
for model in face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx \
             face_recognition_sface/resolve/main/face_recognition_sface_2021dec.onnx; do
  target="ai-service/models/$(basename "$model")"
  [ -f "$target" ] || curl -fsSL -o "$target" "https://huggingface.co/opencv/$model" || echo "   (download failed: $target)"
done

echo "==> Frontend deps"
(cd frontend && npm ci)

echo "==> Backend deps (warm the Maven cache)"
(cd backend && mvn -B -q dependency:go-offline || true)

cat <<'EOF'

Ready. Typical dev loop (one terminal each):
  docker compose up -d postgres ai-service      # db + AI service in Docker
  cd backend && mvn spring-boot:run             # API on :8080
  cd frontend && npm start                      # UI on :4200 (open the forwarded 4200 URL)

Or everything as pods on a local Kubernetes cluster (kind):
  bash k8s/up.sh                                # builds, deploys, forwards the UI to :4200
  kubectl -n houwiya get pods                   # see them; k8s/down.sh removes the cluster
EOF
