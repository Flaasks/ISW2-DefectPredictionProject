This repository is a small Java (Maven) research tool to generate datasets from Git/JIRA histories,
preprocess them with Weka, evaluate classifiers and run a "what-if" analysis pipeline.

Key facts for an AI coding agent (short and actionable)
- Language & build: Java 21, Maven. Build with a JDK 21 toolchain.
- Entry point: `com.dipalma.whatif.Main` (file: `src/main/java/com/dipalma/whatif/Main.java`).
- Principal data artifacts: CSVs in repo root. Naming convention: `<PROJECT>.csv` and processed files use the `_processed.csv` suffix (e.g. `BOOKKEEPER_processed.csv`).

Big-picture architecture (components & flow)
- Connectors: `com.dipalma.whatif.connectors` — `GitConnector` clones repos into `temp-repo/<name>` and maps commits to methods; `JiraConnector` queries Apache JIRA for releases and bug tickets.
- Dataset generation: `DatasetGenerator` (root package) uses connectors + `MethodTracker` to write `<PROJECT>.csv` (see `DatasetGenerator.java`). Important: only first ~34% of releases are considered when producing datasets.
- Preprocessing: `com.dipalma.whatif.preprocessing.DataPreprocessor` loads CSV -> removes identifier columns (`Project`, `MethodName`, `Release`) -> sanitizes NaN/Inf -> winsorizes outliers -> removes constant attributes -> normalizes -> writes `<PROJECT>_processed.csv`.
- Classification: `com.dipalma.whatif.classification.ClassifierRunner` loads processed CSVs, ensures class attribute is nominal, evaluates RandomForest/NaiveBayes/IBk with repeated 10x10 CV and can train+serialize classifiers.
- Analysis & Simulation: `com.dipalma.whatif.analysis.DataAnalyzer`, `FeatureComparer`, `WhatIfSimulator` perform feature ranking (InfoGain), select an actionable feature (LOC/CyclomaticComplexity/ParameterCount/Duplication), pick a high-impact method (AFMethod), and run the what-if pipeline that produces `<prefix>_whatif_summary.csv`.

Important project-specific conventions and gotchas (do not invent)
- CSV method identifier format: `filepath/to/File.java/MethodName(params)` (used across `DatasetGenerator` and `DataAnalyzer`).
- Actionable feature candidates: exactly ["LOC", "CyclomaticComplexity", "ParameterCount", "Duplication"] — `DataAnalyzer` searches ranked Weka attributes and picks the first matching one.
- Release cutoff heuristic: `DatasetGenerator` analyzes only the first floor(releases.size * 0.34) releases (minimum 1). This affects dataset time windows.
- FeatureComparer: when comparing original vs refactored snippets it deliberately increments `NR` and `NAuth` by 1 for the refactored side — tests or changes touching this logic must preserve that behavior unless you update requirements.
- Processed filename suffix: `_processed.csv` — many components expect this exact suffix (e.g., `WhatIfSimulator` expects `*_processed.csv`).
- Logging: SLF4J + Logback. Config: `src/main/resources/logback.xml` (modify to increase verbosity during debugging).

Build / run / debug (practical commands you can run locally)
- Prereq: JDK 21 installed and MAVEN on PATH.
- Build classes and pull deps:
  1) mvn -DskipTests package
  2) mvn dependency:copy-dependencies -DoutputDirectory=target/dependency
  3) Run the main class from the project root (zsh):
     java -cp target/classes:target/dependency/* com.dipalma.whatif.Main

  This will run the full pipeline (dataset generation, preprocessing, classification, what-if analysis). If you already have `BOOKKEEPER_processed.csv` or `SYNCOPE_processed.csv` in the repo root, dataset generation / preprocessing steps are skipped to save time.

Notes about networked integrations
- `GitConnector` clones repos into `temp-repo/<projectKey>` (example: `temp-repo/bookkeeper`). Ensure the running user has network access and sufficient disk space.
- `JiraConnector` is currently hard-coded to query `https://issues.apache.org/jira` (no auth). For private JIRA instances or different projects you'll need to modify `JiraConnector` to provide credentials and/or a different base URL.

Quick examples to reason about change impact
- If you change the preprocessing pipeline (e.g., remove winsorization), update `DataPreprocessor.processData()` and verify downstream `ClassifierRunner.loadAndPrepareData()` still accepts the class attribute as nominal.
- If you change the actionable-feature list, update both `DataAnalyzer` (selection logic) and `WhatIfSimulator` (caller relies on `FeatureAnalyzer.selectTopActionableFeature`).

Files worth reading for context
- `src/main/java/com/dipalma/whatif/DatasetGenerator.java` (dataset creation + bug→method mapping)
- `src/main/java/com/dipalma/whatif/connectors/GitConnector.java` and `JiraConnector.java` (git + jira integration specifics)
- `src/main/java/com/dipalma/whatif/preprocessing/DataPreprocessor.java` (data cleaning rules: NaN handling, winsorize, remove constant features)
- `src/main/java/com/dipalma/whatif/classification/ClassifierRunner.java` (classifier evaluation & I/O expectations)
- `src/main/java/com/dipalma/whatif/analysis/WhatIfSimulator.java` (end-to-end what-if pipeline and summary CSV layout)

If a `.github/copilot-instructions.md` existed already: merge rather than replace — preserve any repository-specific guardrails. No such file was found when this guidance was generated.

If anything above is unclear or you'd like me to expand a section (example: exact `JiraConnector` auth options, or produce a small reproducible test harness that runs only the classifier stage), tell me which section to expand and I'll iterate.
