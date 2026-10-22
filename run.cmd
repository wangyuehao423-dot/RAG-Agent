@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

rem ============================================================
rem  Start the app with API keys injected at runtime.
rem  Keys are read from .env (git-ignored) and exported as
rem  environment variables. Nothing is written into config files.
rem ============================================================

cd /d "%~dp0"

if not exist ".env" (
  echo [ERROR] .env not found.
  echo         Copy .env.example to .env and fill in your API keys.
  exit /b 1
)

for /f "usebackq eol=# tokens=1,* delims==" %%a in (".env") do (
  if not "%%a"=="" set "%%a=%%b"
)

if "!DEEPSEEK_API_KEY!"=="" (
  echo [ERROR] DEEPSEEK_API_KEY is empty in .env
  exit /b 1
)
if "!DASHSCOPE_API_KEY!"=="" (
  echo [ERROR] DASHSCOPE_API_KEY is empty in .env
  exit /b 1
)

echo [INFO] API keys injected from .env ^(config files stay clean^).
echo [INFO] Starting Spring Boot...

rem mvnw.cmd = 项目自带的 Maven Wrapper，不依赖本机是否装过 Maven
call mvnw.cmd spring-boot:run
