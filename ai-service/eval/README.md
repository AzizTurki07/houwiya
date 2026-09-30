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

## Findings

Current set: 4 CIN fronts, 4 CIN backs (8 people's cards, including two married women's) and
1 passport, all phone photos taken in the hand. Numbers below are for the 36 CIN fields.

| Pipeline version (Tesseract) | Exact CIN fields | Mean CER |
|---|---|---|
| Tuned on one card only (baseline on the 8-card set) | 14/36 (39%) | 0.47 |
| + adaptive ink threshold (Otsu per band) | 19/36 | 0.44 |
| + row detection with valley splitting, snapped to the layout | 18/36 | 0.32 |
| + dictionary correction (places, address words, professions) | 21/36 | 0.25 |
| + ink threshold boundary fix | **21/36 (58%)** | **0.21** |

What each step taught us:

- **One card is not a benchmark.** Parameters tuned on a single card dropped to 39% exact on
  eight. Text darkness alone varied from lightness 27 to 144 between photos (lighting, a
  faded print), so fixed thresholds could not work.
- **Card detection** had to handle cards held in the hand: fingers hide corners and the card
  often runs off the frame. The detector fits the card edges as lines, infers a hidden side
  from the ID-1 aspect ratio, and uses the photo border when the card runs off it. It found
  all 8 cards correctly.
- **Ink filtering** keeps only the black value text (the purple labels, background art and
  the stamp are dropped by colour); the dark/light split is computed per field band. Arabic
  dots must survive speck removal -- they are what distinguish ب/ت/ث/ن/ي -- while isolated
  marks away from any letter (label remnants) must not, or they OCR as runs of zeros.
- **Row positions vary between cards**, so each field is snapped to the text row actually
  found near its usual position. Closely spaced lines touch through ascenders/descenders and
  are split at the valley between them.
- **Married women's cards use another layout**: the name line reads "<name> بنت <father> بن
  <grandfather>" and the next line is the husband ("حرم ..."). The lineage is moved out of the
  name, and the spouse line is dropped (not needed for verification -- like the mother's name
  on the back, it is never returned).
- **Tesseract's Arabic model misreads Latin digits** inside Arabic lines ("26" -> "6"); digit
  groups are re-read with the digits-only English model.
- **Most remaining errors were one-letter misses of known words** (بنزرت read six different
  ways). Replacing a token by a governorate/town, generic address word or profession is safe
  only for true near misses: a similarity ratio wrongly turned the name "سالم" into the town
  "الجم", so correction uses edit distance (1 letter for words up to 6 letters). Sample-specific
  neighbourhood names are deliberately *not* in the dictionaries.
- **`tessdata_best` Arabic was worse than the default `tessdata_fast`** on these crops.
- **PaddleOCR vs Tesseract** (same preprocessed field images): PaddleOCR reads dates and places
  better (23/36 exact vs 21/36) but its mean CER is higher (0.32 vs 0.21), it is more often
  confidently wrong (7 vs 5 fields wrong at >= 0.7 confidence -- the ones the review screen
  would not flag), and it adds ~1 GB to the image. Tesseract stays in production; a hybrid
  (PaddleOCR for dates only) is an option if the image size is acceptable.
- **Names remain the weak spot** (25% exact first names for both engines): an open vocabulary,
  so no dictionary can help. This is why every flagged document goes to human review with the
  photo, and why the applicant confirms every field.
- **Passport**: all 7 MRZ fields exact. The evaluation caught a bug where expired passports had
  their expiry pushed a century ahead (2020 -> 2120) and so were never flagged as expired.
