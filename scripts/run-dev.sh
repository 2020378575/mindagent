#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if [ -z "${JAVA_HOME:-}" ] && [ -d "$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home" ]; then
  export JAVA_HOME="$ROOT_DIR/.tools/amazon-corretto-17.jdk/Contents/Home"
fi
if [ -x "$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn" ]; then
  DEFAULT_MAVEN_BIN="$ROOT_DIR/.tools/apache-maven-3.9.9/bin/mvn"
else
  DEFAULT_MAVEN_BIN="$(command -v mvn || true)"
fi
MAVEN_BIN="${MAVEN_BIN:-$DEFAULT_MAVEN_BIN}"

if [ ! -x "$MAVEN_BIN" ]; then
  echo "Cannot find Maven."
  echo "Install Maven or set MAVEN_BIN to the mvn executable path."
  exit 1
fi

if [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "OPENAI_API_KEY is required."
  echo "The app now calls an OpenAI-compatible HTTP API instead of a local fine-tuned model."
  echo "Optional: OPENAI_BASE_URL (default https://api.openai.com) and OPENAI_MODEL (default gpt-4o-mini)."
  exit 1
fi

mkdir -p data

AI_PROVIDER="${AI_PROVIDER:-openai}" \
OPENAI_BASE_URL="${OPENAI_BASE_URL:-https://api.openai.com}" \
OPENAI_MODEL="${OPENAI_MODEL:-gpt-4o-mini}" \
  "$MAVEN_BIN" -Dmaven.repo.local=.m2/repository spring-boot:run
