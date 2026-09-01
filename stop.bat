@echo off
chcp 65001 >nul
taskkill /FI "WINDOWTITLE eq tiaoxiu-backend" /T /F >nul 2>&1
if %errorlevel%==0 (
  echo 已停止调休管家后端（tiaoxiu-backend）。
) else (
  echo 未找到标题为 tiaoxiu-backend 的进程。
  echo 若你是用 VS Code 集成终端直接 java -jar 启动的，请到那个终端按 Ctrl+C 停止。
)
