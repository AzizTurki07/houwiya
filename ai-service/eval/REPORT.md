# Extraction accuracy report

Generated 2026-09-30 by `eval/evaluate.py` on 9 labelled sample(s). Values are compared after folding Arabic letter variants (ا/أ/إ, ي/ى, ه/ة).

- **Exact**: share of samples where the field matched the label exactly.
- **CER**: character error rate (edit distance / label length; 0 is perfect).
- **Confident but wrong**: field wrong yet confidence >= 0.7, i.e. it would not be flagged on the review screen. This is the number to drive to zero.

## tesseract

| Document | Field | n | Exact | CER | Mean confidence | Confident but wrong |
|---|---|---|---|---|---|---|
| cin_back | address | 4 | 50% | 0.31 | 0.68 | 0 |
| cin_back | issue_date | 4 | 75% | 0.25 | 0.43 | 0 |
| cin_back | profession | 4 | 75% | 0.19 | 0.65 | 0 |
| cin_front | date_of_birth | 4 | 75% | 0.25 | 0.57 | 0 |
| cin_front | document_number | 4 | 100% | 0.00 | 0.93 | 0 |
| cin_front | first_name | 4 | 25% | 0.28 | 0.72 | 2 |
| cin_front | last_name | 4 | 25% | 0.44 | 0.53 | 1 |
| cin_front | lineage | 4 | 25% | 0.11 | 0.64 | 1 |
| cin_front | place_of_birth | 4 | 75% | 0.10 | 0.51 | 1 |
| passport | date_of_birth | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | document_number | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | expiry_date | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | given_names | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | nationality | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | sex | 1 | 100% | 0.00 | 0.98 | 0 |
| passport | surname | 1 | 100% | 0.00 | 0.98 | 0 |

**Overall (tesseract)**: 65% of fields exact, mean CER 0.18.

## paddle

| Document | Field | n | Exact | CER | Mean confidence | Confident but wrong |
|---|---|---|---|---|---|---|
| cin_back | address | 4 | 25% | 0.23 | 0.92 | 3 |
| cin_back | issue_date | 4 | 100% | 0.00 | 0.95 | 0 |
| cin_back | profession | 4 | 75% | 0.19 | 0.89 | 0 |
| cin_front | date_of_birth | 4 | 100% | 0.00 | 0.99 | 0 |
| cin_front | document_number | 4 | 100% | 0.00 | 1.00 | 0 |
| cin_front | first_name | 4 | 25% | 1.40 | 0.92 | 3 |
| cin_front | last_name | 4 | 50% | 0.50 | 0.41 | 0 |
| cin_front | lineage | 4 | 0% | 0.53 | 0.37 | 1 |
| cin_front | place_of_birth | 4 | 100% | 0.00 | 0.95 | 0 |

**Overall (paddle)**: 64% of fields exact, mean CER 0.32.

