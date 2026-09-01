@echo off
chcp 65001 >nul
setlocal
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot
cd /d "%~dp0server-java"

if not exist target\server-java-1.0.0.jar (
  echo [错误] 未找到 jar 包（target\server-java-1.0.0.jar）
  echo 请先在 server-java 目录执行： mvn -DskipTests package
  pause
  exit /b 1
)

echo 正在启动调休管家后端（端口 8600，需 MySQL 3306 已运行）...
start "tiaoxiu-backend" "%JAVA_HOME%\bin\java.exe" -jar target/server-java-1.0.0.jar --server.port=8600

echo.
echo 后端已在后台启动，窗口标题为 "tiaoxiu-backend"。
echo   - 浏览器访问： http://localhost:8600
echo   - 关闭方式 1： 运行 stop.bat
echo   - 关闭方式 2： 直接关闭名为 tiaoxiu-backend 的窗口
echo   - 关闭方式 3： 在 VS Code 集成终端用 Ctrl+C（若在那里直接启动）
endlocal
