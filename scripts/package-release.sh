#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_NAME="EvidenceLab"
DATASET_FILE="${DATASET_FILE:-$ROOT_DIR/data/lora/research-assistant.jsonl}"
STAMP="$(date +%Y%m%d-%H%M%S)"
DIST_DIR="$ROOT_DIR/dist"
STAGE_DIR="$DIST_DIR/stage-$STAMP"
ARCHIVE="$DIST_DIR/${PROJECT_NAME}-app-$STAMP.tar.gz"

mkdir -p "$DIST_DIR"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/$PROJECT_NAME"

RSYNC_EXCLUDES=(
  --exclude '.git/'
  --exclude '.idea/'
  --exclude '.vscode/'
  --exclude '.m2/'
  --exclude '.tools/'
  --exclude 'target/'
  --exclude 'dist/'
  --exclude 'logs/'
  --exclude '.superpowers/'
  --exclude '*.pdf'
  --exclude '.DS_Store'
  --exclude '*.iml'
  --exclude '*.log'
  --exclude '*.gguf'
  --exclude '*.gguf.zip'
  --exclude '*.zip'
  --exclude 'run.log'
  --exclude 'data/**'
)

rsync -a "$ROOT_DIR/" "$STAGE_DIR/$PROJECT_NAME/" "${RSYNC_EXCLUDES[@]}"

if [ -f "$DATASET_FILE" ]; then
  mkdir -p "$STAGE_DIR/$PROJECT_NAME/data/lora"
  cp "$DATASET_FILE" "$STAGE_DIR/$PROJECT_NAME/data/lora/$(basename "$DATASET_FILE")"
else
  echo "Warning: dataset not found at $DATASET_FILE (continuing without bundling LoRA data)."
fi

(
  cd "$STAGE_DIR"
  COPYFILE_DISABLE=1 tar -czf "$ARCHIVE" "$PROJECT_NAME"
)

rm -rf "$STAGE_DIR"

echo "Created release package:"
echo "$ARCHIVE"
echo "Model GGUF is intentionally excluded. Send the model zip separately."
echo
echo "Package size:"
du -sh "$ARCHIVE"
