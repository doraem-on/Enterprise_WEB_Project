# Data and models

## Participant and activity data

All seeded people, institutions, attendance, projects, and achievements are fictional demonstration records. The program catalog is authored for this prototype. These are not prison outcome datasets and cannot establish rehabilitation effectiveness.

## Career data

`src/main/resources/data/careers.json` contains a reproducible selection of 16 occupations from the **O*NET 30.0 Database**, U.S. Department of Labor, Employment and Training Administration. For each occupation, its description and six highest-importance skills are retained. The selection and field names are adaptations. Source: https://www.onetcenter.org/database.html ; archived source ZIP: https://www.onetcenter.org/dl_files/database/db_30_0_text.zip . License: Creative Commons Attribution 4.0, https://creativecommons.org/licenses/by/4.0/ . O*NET is a trademark of USDOL/ETA; use does not imply endorsement.

Reproduce the subset with `python3 scripts/prepare_onet.py path/to/db_30_0_text.zip`. This is U.S. occupational reference data. Local qualifications, accessibility, employment eligibility, language, and labour-market suitability require human review. Occupation suggestions never grant or deny a job or opportunity.

## Semantic matching

Pretrained model: `sentence-transformers/all-MiniLM-L6-v2` (Apache-2.0), https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2 . The ONNX export runs locally in Java using ONNX Runtime; DJL's tokenizer supplies token IDs, masks, and type IDs. Attention-masked mean pooling and L2 normalization produce 384-dimensional sentence vectors. Ranking uses cosine similarity. Model inputs are self-reported skills, interests, and learning goals, compared to program/occupation descriptions and skills. Profile text is not sent to an external inference provider.

We do not train this model or claim a measured rehabilitation success rate. The UI's text similarity is not a calibrated probability. The model is primarily English; multilingual validity is not established. Already requested/enrolled/completed programs are excluded. Prerequisites and activity suitability are explicitly staff-reviewed, not inferred by the model. Full programs are labelled for waiting-list consideration. Missing models trigger a labelled token-overlap fallback; model exceptions also change the displayed label.

`scripts/download_models.py` verifies SHA-256 checksums. Model binaries are ignored by Git. Runtime inference caches remain in memory; no profile vectors are exported. Recommendation checks compare relevant and unrelated text; a small manually labelled evaluation is separate from real-world validation.

## Scheduling

Timefold is a constraint optimizer, not a trained ML model. Each active program receives at most one new one-hour session in next week's Monday–Friday window (09:00, 11:00, or 14:00). Existing sessions are preserved. Candidate slots exclude existing room, instructor, and enrolled-participant clashes. The solver penalizes clashes between new sessions and prefers earlier slots. A final database check rejects infeasible solutions; saves are transactional. Running it twice does not duplicate next week's sessions.

## Completion and credentials

A new certificate requires attendance at the program's configured number of sessions, reading all required lessons, passing each required lesson quiz at 70% or above, and passing each required assignment/practical assessment at 70% or above. Quizzes support multiple questions and repeated attempts. Staff assess written responses or record observations against practical rubrics; this is not a validated competency instrument. Existing certificates remain valid after new curriculum is introduced. Existing skills are self-reported. Certificates are internal completion records, not accredited qualifications. Public verification discloses only credential ID, program, and issue date; identifying profiles require authentication.

## Feedback and reviewed evaluation

Participants can explicitly opt into a 1–5 relevance rating with an optional note, replace that rating, or withdraw it. Ratings are aggregated separately from benchmark metrics; they do not automatically retrain the model or affect access decisions.

Staff and instructors author de-identified relevance queries, expected programs, and rationales. A different account must validate the label before it is included in a benchmark. Runs persist top-1 matches, hit@5, a keyword baseline, model status, and per-case query/expected/predicted results. This supports an independent-review process; distinct accounts alone do not prove independent human judgement. Representative labels, held-out evaluation, multilingual validation and a real relevance-feedback collection period remain institutional evaluation work. No benchmark data or human ratings are fabricated by the demo seed.

The eight committed authored smoke cases remain development tests, not the reviewed benchmark. Their current reports are generated under `target/` during tests. Interface translation does not make MiniLM a validated Hindi/Punjabi recommender.
