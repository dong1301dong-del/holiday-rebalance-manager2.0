@echo off
chcp 65001 >nul
setlocal
set JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot

REM === 载入敏感环境变量（数据库口令、JWT 密钥） ===
REM 这些值不再写死在本脚本或 application.yml 中，而是由同目录的 env.local.bat 提供；
REM 该文件已被 .gitignore 忽略，请勿提交到代码仓库。
if exist "%~dp0env.local.bat" (
  call "%~dp0env.local.bat"
) else (
  echo [错误] 未找到 env.local.bat
  echo        请在同目录创建该文件并至少设置： DB_USERNAME / DB_PASSWORD / JWT_SECRET
  echo        可参考仓库说明中的示例；缺少这些变量后端会启动失败（这是刻意的安全设计）。
  pause
  exit /b 1
)

if "%DB_USERNAME%"=="" (
  echo [错误] env.local.bat 中未设置 DB_USERNAME，后端会拒绝启动。
  pause
  exit /b 1
)
if "%DB_PASSWORD%"=="" (
  echo [错误] env.local.bat 中未设置 DB_PASSWORD，后端会拒绝启动。
  pause
  exit /b 1
)
if "%JWT_SECRET%"=="" (
  echo [错误] env.local.bat 中未设置 JWT_SECRET，后端会拒绝启动。
  pause
  exit /b 1
)

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
