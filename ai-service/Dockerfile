# syntax=docker/dockerfile:1.7
FROM python:3.11-slim
WORKDIR /app

# PassportEye and pytesseract both shell out to the tesseract binary --
# it must be installed as a system package (pip install tesseract is a
# different, unrelated library and will NOT work here). fra+ara language
# packs are needed for the bilingual CIN pipeline.
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-fra \
    tesseract-ocr-ara \
    libgl1 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Face match models (OpenCV Zoo; YuNet: MIT, SFace: Apache-2.0), pinned by checksum so a
# changed upstream file fails the build instead of silently changing the matcher.
ADD --checksum=sha256:8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4 \
    https://huggingface.co/opencv/face_detection_yunet/resolve/main/face_detection_yunet_2023mar.onnx \
    /app/models/face_detection_yunet_2023mar.onnx
ADD --checksum=sha256:0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79 \
    https://huggingface.co/opencv/face_recognition_sface/resolve/main/face_recognition_sface_2021dec.onnx \
    /app/models/face_recognition_sface_2021dec.onnx

COPY app ./app
COPY tests ./tests
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
