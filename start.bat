@echo off
setlocal EnableExtensions

REM ============================================================
REM  调休管家 - 一键启动后端
REM
REM  与旧版的区别：
REM   1) 启动后轮询 /api/auth/health，真正验证服务是否起来了，
REM      不再像旧版那样不看结果就打印"已在后台启动"。
REM   2) 启动前先检查 8600 端口是否已被占用，避免"窗口一闪就没了"却不知原因。
REM   3) 用 cmd /k 包住 java，万一启动失败窗口不会瞬间关闭，能看到报错。
REM   4) 打印一次成功/失败结论，然后 3 秒自动关窗（双击即可，无需按键）。
REM
REM  需要同目录的 env.local.bat 提供 DB_USERNAME / DB_PASSWORD / JWT_SECRET
REM ============================================================

set "PORT=8600"
set "ROOT=%~dp0"
if "%ROOT:~-1%"=="\" set "ROOT=%ROOT:~0,-1%"
set "APPDIR=%ROOT%\server-java"
set "JAR=%APPDIR%\target\server-java-1.0.0.jar"
set "LOG=%APPDIR%\backend.log"
set "ERRLOG=%APPDIR%\backend.err.log"
set "RC=0"
set "BUSY_PID="

REM ---------- 1. 找 JDK ----------
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
if not exist "%JAVA_HOME%\bin\java.exe" (
  set "RC=1"
  echo [错误] 找不到 JDK: %JAVA_HOME%\bin\java.exe
  echo         请把本脚本开头的 JAVA_HOME 改成你的 JDK 17 安装路径。
  goto :DONE
)

REM ---------- 2. 载入敏感环境变量 ----------
if not exist "%ROOT%\env.local.bat" (
  set "RC=1"
  echo [错误] 找不到 env.local.bat
  echo         请在同目录创建该文件，至少包含三行:
  echo           set DB_USERNAME=数据库账号
  echo           set DB_PASSWORD=数据库口令
  echo           set JWT_SECRET=至少32位的随机密钥
  goto :DONE
)
call "%ROOT%\env.local.bat"

for %%V in (DB_USERNAME DB_PASSWORD JWT_SECRET) do (
  if not defined %%V (
    set "RC=1"
    echo [错误] env.local.bat 里没有设置 %%V，后端会拒绝启动（这是刻意的安全设计）。
    echo         提示：若文件里确实写了「set %%V=...」，多半是保存编码不对——请用 GBK（ANSI）编码 + CRLF 换行重新保存。
    goto :DONE
  )
)

REM ---------- 3. jar 是否存在 ----------
if not exist "%JAR%" (
  set "RC=1"
  echo [错误] 找不到 jar: %JAR%
  echo         请先在 server-java 目录执行:  mvn -DskipTests package
  goto :DONE
)

REM ---------- 4. 端口是否已被占用 ----------
call :PortPid %PORT%
if defined BUSY_PID (
  set "RC=1"
  echo [错误] 端口 %PORT% 已被 PID %BUSY_PID% 占用，后端可能已经在运行了。
  echo         如需重启: 先双击 stop.bat 停止，再运行本脚本。
  goto :DONE
)

REM ---------- 5. 启动 ----------
echo 正在启动调休管家后端（端口 %PORT%）...
echo   JDK : %JAVA_HOME%
echo   JAR : %JAR%
echo   日志: %LOG%
echo.
cd /d "%APPDIR%"
REM 用 PowerShell 的 Start-Process 以「隐藏窗口」方式拉起后端：不再弹出 Spring Boot 控制台窗口。
REM 输出写入日志文件；该进程独立于本窗口，本窗口倒计时结束后关闭也不会把它带走。
REM 日志按 UTF-8 写入（-DCONSOLE_LOG_CHARSET=UTF-8），用 VSCode 打开即可正常显示中文。
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%JAVA_HOME%\bin\java.exe' -ArgumentList '-DCONSOLE_LOG_CHARSET=UTF-8','-jar','%JAR%','--server.port=%PORT%' -WorkingDirectory '%APPDIR%' -WindowStyle Hidden -RedirectStandardOutput '%LOG%' -RedirectStandardError '%ERRLOG%'"

REM ---------- 6. 轮询健康检查，最多等约 40 秒 ----------
set "HAS_CURL=0"
if exist "%SystemRoot%\System32\curl.exe" set "HAS_CURL=1"
echo 正在等待服务就绪（最多 40 秒）...
set /a TRIES=0

:WAIT
set /a TRIES+=1
if "%HAS_CURL%"=="1" (
  "%SystemRoot%\System32\curl.exe" -s -o nul --max-time 2 "http://127.0.0.1:%PORT%/api/auth/health" >nul 2>&1
) else (
  set "BUSY_PID="
  call :PortPid %PORT%
)
if "%HAS_CURL%"=="1" if not errorlevel 1 goto :STARTED
if "%HAS_CURL%"=="0" if defined BUSY_PID goto :STARTED
if %TRIES% GEQ 20 goto :FAILED
ping -n 3 127.0.0.1 >nul
goto :WAIT

:STARTED
call :PortPid %PORT%
echo.
echo [启动成功] 后端已就绪，PID=%BUSY_PID%
echo   访问地址: http://localhost:%PORT%
echo   停止方式: 双击 stop.bat
echo   运行日志: %LOG%
goto :DONE

:FAILED
set "RC=1"
echo.
echo [启动失败] 等待 40 秒后健康检查仍未通过。
echo   请查看日志文件里的报错: %LOG%，常见原因:
echo     1. MySQL 未启动，或 3306 端口没有监听
echo     2. env.local.bat 里的数据库账号或口令不正确
echo     3. 数据库表结构与代码不一致（ddl-auto=validate 会拒绝启动）
echo     4. 缺少环境变量，SecurityStartupValidator 拒绝启动
goto :DONE

REM ---------- 收尾：打印结果 + 3 秒后自动关窗 ----------
:DONE
echo.
if "%RC%"=="0" (
  echo 脚本执行结果：成功。本窗口 3 秒后自动关闭...
) else (
  echo 脚本执行结果：失败（原因见上方提示）。本窗口 3 秒后自动关闭...
)
ping -n 4 127.0.0.1 >nul
endlocal & exit /b %RC%

REM ---------- 子程序: 取监听指定端口的进程 PID ----------
:PortPid
set "BUSY_PID="
for /f "tokens=5" %%p in ('netstat -ano -p TCP ^| findstr "LISTENING" ^| findstr /C:":%1 "') do set "BUSY_PID=%%p"
goto :eof
