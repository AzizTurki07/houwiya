"""
Accuracy evaluation for the extraction pipelines against a labelled sample set.

    # inside the AI service image (has Tesseract fra+ara):
    docker run --rm -v "$PWD/ai-service:/app" -w /app -e PYTHONPATH=/app houwiya-ai \
        python eval/evaluate.py
    # also benchmark PaddleOCR (evaluation image, see Dockerfile.eval):
    docker run ... houwiya-ai-eval python eval/evaluate.py --engines tesseract,paddle

Inputs (both git-ignored -- they are real people's identity documents):
    eval/samples/<file>     photos
    eval/labels.json        ground truth, format in labels.example.json

Outputs:
    eval/out/results.json   per-sample predictions (contains personal data: git-ignored)
    eval/REPORT.md          aggregate metrics only, no values -- safe to commit

Every engine reads the *same* preprocessed field images produced by the production
pipeline (card detection, ink filter), so the comparison isolates the recognition step.
"""
import argparse
import datetime
import json
import pathlib
import sys
from collections import defaultdict
from typing import Callable, Dict, Tuple

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from app.arabic_text import fold  # noqa: E402
from app.cin_extraction import FieldBand, extract_cin_back_fields, extract_cin_fields, tesseract_read  # noqa: E402
from app.mrz_extraction import extract_passport_mrz  # noqa: E402

REVIEW_THRESHOLD = 0.7  # backend app.review.min-confidence


def levenshtein(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def normalise(value) -> str:
    # fold() unifies letter variants people and OCR use interchangeably (ا/أ/إ, ي/ى, ه/ة);
    # Latin passport values just get whitespace/case normalised.
    return fold(str(value or "")).upper()


def paddle_reader() -> Callable:
    from paddleocr import PaddleOCR  # only installed in the evaluation image
    import cv2

    # enable_mkldnn=False: Paddle 3.x oneDNN CPU kernels fail on these models
    # ("ConvertPirAttribute2RuntimeAttribute not support").
    ocr = PaddleOCR(lang="ar", device="cpu", enable_mkldnn=False,
                    use_doc_orientation_classify=False, use_doc_unwarping=False,
                    use_textline_orientation=False)

    def read(image, band: FieldBand) -> Tuple[str, float]:
        result = ocr.predict(cv2.cvtColor(image, cv2.COLOR_GRAY2BGR))[0]
        items = list(zip(result["rec_texts"], result["rec_scores"], result["rec_polys"]))
        if band.block:  # rows top to bottom, then right-to-left within a row
            items.sort(key=lambda t: (int(t[2][:, 1].min() // 40), -t[2][:, 0].max()))
        else:  # one line: purely right-to-left, whatever the box heights
            items.sort(key=lambda t: -t[2][:, 0].max())
        texts = [t for t, _, _ in items if t.strip()]
        scores = [s for t, s, _ in items if t.strip()]
        return " ".join(texts), (sum(scores) / len(scores)) if scores else 0.0

    return read


def run(engines, labels_path: pathlib.Path, samples_dir: pathlib.Path):
    labels = json.loads(labels_path.read_text(encoding="utf-8"))["samples"]
    readers: Dict[str, Callable] = {"tesseract": tesseract_read}
    if "paddle" in engines:
        readers["paddle"] = paddle_reader()

    rows = []
    for sample in labels:
        image = (samples_dir / sample["file"]).read_bytes()
        kind = sample["kind"]
        for engine in engines:
            if kind == "passport":
                if engine != "tesseract":
                    continue  # MRZ goes through PassportEye, not a swappable line reader
                result = extract_passport_mrz(image).model_dump()
            elif kind == "cin_front":
                result = extract_cin_fields(image, reader=readers[engine]).model_dump()
            elif kind == "cin_back":
                result = extract_cin_back_fields(image, reader=readers[engine]).model_dump()
            else:
                raise ValueError(f"unknown kind {kind!r} for {sample['file']}")
            for field, truth in sample["fields"].items():
                pred = result.get(field)
                t, p = normalise(truth), normalise(pred)
                rows.append({
                    "file": sample["file"], "kind": kind, "engine": engine, "field": field,
                    "truth": truth, "pred": pred,
                    "exact": t == p,
                    "cer": levenshtein(p, t) / max(1, len(t)),
                    "confidence": result.get("field_confidence", {}).get(field),
                })
    return rows


def report(rows, engines) -> str:
    groups = defaultdict(list)
    for r in rows:
        groups[(r["engine"], r["kind"], r["field"])].append(r)

    samples = len({r["file"] for r in rows})
    lines = [
        "# Extraction accuracy report",
        "",
        f"Generated {datetime.date.today().isoformat()} by `eval/evaluate.py` on {samples} labelled "
        "sample(s). Values are compared after folding Arabic letter variants (ا/أ/إ, ي/ى, ه/ة).",
        "",
        "- **Exact**: share of samples where the field matched the label exactly.",
        "- **CER**: character error rate (edit distance / label length; 0 is perfect).",
        f"- **Confident but wrong**: field wrong yet confidence >= {REVIEW_THRESHOLD}, i.e. it would "
        "not be flagged on the review screen. This is the number to drive to zero.",
        "",
    ]
    for engine in engines:
        lines += [f"## {engine}", "", "| Document | Field | n | Exact | CER | Mean confidence | Confident but wrong |",
                  "|---|---|---|---|---|---|---|"]
        for (eng, kind, field), rs in sorted(groups.items()):
            if eng != engine:
                continue
            n = len(rs)
            exact = sum(r["exact"] for r in rs) / n
            cer = sum(r["cer"] for r in rs) / n
            confs = [r["confidence"] for r in rs if r["confidence"] is not None]
            conf = f"{sum(confs) / len(confs):.2f}" if confs else "-"
            overconfident = sum(1 for r in rs if not r["exact"] and (r["confidence"] or 0) >= REVIEW_THRESHOLD)
            lines.append(f"| {kind} | {field} | {n} | {exact:.0%} | {cer:.2f} | {conf} | {overconfident} |")
        all_rs = [r for r in rows if r["engine"] == engine]
        if all_rs:
            lines += ["", f"**Overall ({engine})**: {sum(r['exact'] for r in all_rs) / len(all_rs):.0%} of fields exact, "
                      f"mean CER {sum(r['cer'] for r in all_rs) / len(all_rs):.2f}.", ""]
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--engines", default="tesseract", help="comma-separated: tesseract,paddle")
    parser.add_argument("--labels", default=str(HERE / "labels.json"))
    parser.add_argument("--samples", default=str(HERE / "samples"))
    parser.add_argument("--report", default=str(HERE / "REPORT.md"))
    args = parser.parse_args()
    engines = [e.strip() for e in args.engines.split(",") if e.strip()]

    rows = run(engines, pathlib.Path(args.labels), pathlib.Path(args.samples))
    out = HERE / "out"
    out.mkdir(exist_ok=True)
    (out / "results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    text = report(rows, engines)
    pathlib.Path(args.report).write_text(text, encoding="utf-8")
    print(text)


if __name__ == "__main__":
    main()
