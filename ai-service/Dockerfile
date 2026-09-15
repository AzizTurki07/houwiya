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
COPY app ./app
COPY tests ./tests
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
