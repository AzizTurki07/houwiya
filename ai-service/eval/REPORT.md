# Extraction accuracy report

Generated 2026-09-30 by `eval/evaluate.py` on 3 labelled sample(s). Values are compared after folding Arabic letter variants (ا/أ/إ, ي/ى, ه/ة).

- **Exact**: share of samples where the field matched the label exactly.
- **CER**: character error rate (edit distance / label length; 0 is perfect).
- **Confident but wrong**: field wrong yet confidence >= 0.7, i.e. it would not be flagged on the review screen. This is the number to drive to zero.

## tesseract

| Document | Field | n | Exact | CER | Mean confidence | Confident but wrong |
|---|---|---|---|---|---|---|
| cin_back | address | 1 | 100% | 0.00 | 0.87 | 0 |
| cin_back | issue_date | 1 | 100% | 0.00 | 0.72 | 0 |
| cin_back | profession | 1 | 0% | 0.20 | 0.89 | 1 |
| cin_front | date_of_birth | 1 | 100% | 0.00 | 0.58 | 0 |
| cin_front | document_number | 1 | 100% | 0.00 | 0.95 | 0 |
| cin_front | first_name | 1 | 0% | 0.33 | 0.74 | 1 |
| cin_front | last_name | 1 | 100% | 0.00 | 0.87 | 0 |
| cin_front | lineage | 1 | 100% | 0.00 | 0.84 | 0 |
| cin_front | place_of_birth | 1 | 100% | 0.00 | 0.49 | 0 |
| passport | date_of_birth | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | document_number | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | expiry_date | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | given_names | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | nationality | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | sex | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | surname | 1 | 100% | 0.00 | 0.98 | 0 |

**Overall (tesseract)**: 88% of fields exact, mean CER 0.03.

## paddle

| Document | Field | n | Exact | CER | Mean confidence | Confident but wrong |
|---|---|---|---|---|---|---|
| cin_back | address | 1 | 100% | 0.00 | 0.98 | 0 |
| cin_back | issue_date | 1 | 100% | 0.00 | 1.00 | 0 |
| cin_back | profession | 1 | 100% | 0.00 | 0.98 | 0 |
| cin_front | date_of_birth | 1 | 100% | 0.00 | 1.00 | 0 |
| cin_front | document_number | 1 | 100% | 0.00 | 1.00 | 0 |
| cin_front | first_name | 1 | 0% | 0.33 | 0.99 | 1 |
| cin_front | last_name | 1 | 100% | 0.00 | 0.93 | 0 |
| cin_front | lineage | 1 | 0% | 0.06 | 0.86 | 1 |
| cin_front | place_of_birth | 1 | 100% | 0.00 | 1.00 | 0 |

**Overall (paddle)**: 78% of fields exact, mean CER 0.04.

