#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

DATASET="src/main/resources/rag-eval/evidencelab-rag-eval-v1.json"
OUTPUT="docs/evaluation/evidencelab-v1-results.md"
METRICS_JSON="target/evidencelab-metrics.json"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dataset)
      DATASET="${2:?missing dataset}"
      shift 2
      ;;
    --output)
      OUTPUT="${2:?missing output}"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

echo "Running EvidenceLab acceptance suite..."
mvn -Dmaven.repo.local=.m2/repository -Dtest=EvidenceLabAcceptanceTests test

if [[ ! -s "$METRICS_JSON" ]]; then
  echo "Missing metrics file: $METRICS_JSON" >&2
  exit 1
fi

python3 - "$METRICS_JSON" "$OUTPUT" "$DATASET" <<'PY'
import json
import subprocess
import sys
from pathlib import Path

metrics_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
dataset = sys.argv[3]
metrics = json.loads(metrics_path.read_text(encoding="utf-8"))

required = [
    "datasetVersion",
    "gitCommit",
    "intentAccuracy",
    "recallAtFive",
    "claimSourceSupportRate",
    "structuredOutputSuccessRate",
    "crossProjectLeakageCount",
    "taskRecoveryScenariosPassed",
]
missing = [key for key in required if key not in metrics]
if missing:
    raise SystemExit(f"Missing metrics keys: {', '.join(missing)}")

thresholds = {
    "intentAccuracy": 0.90,
    "recallAtFive": 0.85,
    "claimSourceSupportRate": 0.95,
    "structuredOutputSuccessRate": 0.98,
}
for key, minimum in thresholds.items():
    value = float(metrics[key])
    if value < minimum:
        raise SystemExit(f"Threshold failed: {key}={value} < {minimum}")

if int(metrics["crossProjectLeakageCount"]) != 0:
    raise SystemExit("Threshold failed: crossProjectLeakageCount must be 0")
if int(metrics["taskRecoveryScenariosPassed"]) < 4:
    raise SystemExit("Threshold failed: taskRecoveryScenariosPassed must be >= 4")

commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
dataset_version = Path(dataset).name
lines = [
    "# EvidenceLab v1 Evaluation Results",
    "",
    f"- Dataset version: {dataset_version}",
    f"- Git commit: {commit}",
    f"- Intent accuracy: {float(metrics['intentAccuracy']):.4f}",
    f"- Recall@5: {float(metrics['recallAtFive']):.4f}",
    f"- Claim-source support rate: {float(metrics['claimSourceSupportRate']):.4f}",
    f"- Structured output success rate: {float(metrics['structuredOutputSuccessRate']):.4f}",
    f"- Cross-project leakage count: {int(metrics['crossProjectLeakageCount'])}",
    f"- Task recovery scenarios passed: {int(metrics['taskRecoveryScenariosPassed'])}",
    "",
]
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text("\n".join(lines), encoding="utf-8")
print(f"Wrote {output_path}")
PY
