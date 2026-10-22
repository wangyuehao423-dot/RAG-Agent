#!/usr/bin/env bash
# ============================================================
#  Start the app with API keys injected at runtime.
#  Keys are read from .env (git-ignored) and exported into the
#  environment. Nothing is written into config files.
# ============================================================
set -e
cd "$(dirname "$0")"

if [ ! -f .env ]; then
  echo "[ERROR] .env not found."
  echo "        Copy .env.example to .env and fill in your API keys."
  exit 1
fi

set -a
# shellcheck disable=SC1091
. ./.env
set +a

if [ -z "${DEEPSEEK_API_KEY:-}" ]; then
  echo "[ERROR] DEEPSEEK_API_KEY is empty in .env"; exit 1
fi
if [ -z "${DASHSCOPE_API_KEY:-}" ]; then
  echo "[ERROR] DASHSCOPE_API_KEY is empty in .env"; exit 1
fi

echo "[INFO] API keys injected from .env (config files stay clean)."
echo "[INFO] Starting Spring Boot..."
# ./mvnw = 项目自带的 Maven Wrapper，不依赖本机是否装过 Maven
exec ./mvnw spring-boot:run
