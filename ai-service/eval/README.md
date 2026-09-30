# Extraction accuracy evaluation

Measures how well the passport (MRZ) and CIN (front + back) pipelines read real documents,
field by field, and compares OCR engines. The latest aggregate numbers are in
[REPORT.md](REPORT.md).

## Privacy rules

The samples are real people's identity documents. Only use your own documents or those of
people who explicitly agreed, and keep them local:

- `samples/`, `labels.json` and `out/` are git-ignored (see `.gitignore`) and excluded from
  Docker build contexts (`../.dockerignore`). Never force-add them.
- `REPORT.md` contains aggregate metrics only (the evaluator never writes values into it), so
  it is safe to commit. Per-sample predictions go to `out/results.json`, which stays local.

## Adding samples

1. Put photos in `samples/` (phone photos are fine: in the hand, at an angle).
2. Copy `labels.example.json` to `labels.json` and describe each photo: `kind` is
   `cin_front`, `cin_back` or `passport`; `fields` holds the true values exactly as printed
   (Arabic for the CIN, dates as `yyyy-mm-dd`).

The roadmap target is 10-20 CIN photos. With fewer, treat the numbers as indicative.

## Running

The pipelines need Tesseract with the Arabic data, so run inside the AI service image:

```bash
docker build -t houwiya-ai ai-service
docker run --rm -v "$PWD/ai-service:/app" -w /app houwiya-ai python eval/evaluate.py
```

To include the PaddleOCR comparison, build the evaluation-only image (adds ~1 GB, never
deployed) and pass `--engines`:

```bash
docker build -t houwiya-ai-eval -f ai-service/eval/Dockerfile.eval ai-service
docker run --rm -v "$PWD/ai-service:/app" -w /app houwiya-ai-eval \
  python eval/evaluate.py --engines tesseract,paddle
```

On Windows Git Bash, prefix with `MSYS_NO_PATHCONV=1` and use `$(pwd -W)` instead of `$PWD`.

## What the metrics mean

- **Exact**: the field matched the label after folding Arabic letter variants people use
  interchangeably (ا/أ/إ, ي/ى, ه/ة).
- **CER**: character error rate, edit distance / label length (0 = perfect).
- **Confident but wrong**: wrong, yet confidence >= 0.7, so the review screen would *not* flag
  it. This is the most important number: a flagged mistake gets caught by the user or an admin,
  an unflagged one doesn't.

## Findings so far (1 passport, 1 CIN front + back)

- **Card detection** had to be rebuilt for hand-held photos: fingers hide corners and the card
  often runs off the frame. The detector now fits the card edges as lines, infers a hidden side
  from the ID-1 aspect ratio, and treats the photo border as an edge when the card runs off it.
- **Ink filtering** is the biggest single win: keeping only the black value text (dropping the
  purple labels, background art and stamp) took the text-field error rate from 0.37 to 0.25
  before any other change. Arabic dots must survive speck removal: they are what distinguish
  ب/ت/ث/ن/ي.
- **Tesseract's Arabic model misreads Latin digits** inside Arabic lines ("26" -> "6"). Digit
  groups are re-read with the digits-only English model.
- **`tessdata_best` Arabic was worse than the default `tessdata_fast`** on these crops
  (CER 0.375 vs 0.252), so the image keeps the packaged model.
- **PaddleOCR vs Tesseract**: both read 7/9 CIN fields exactly. PaddleOCR gets slightly more
  letters right, but it is overconfident on its mistakes (0.99 on a wrong name) and adds ~1 GB.
  Tesseract stays in production. Revisit with a larger sample set.
- **Passport**: all 7 fields exact. The evaluation caught a bug where expired passports had
  their expiry pushed a century ahead (2020 -> 2120) and so were never flagged as expired.
