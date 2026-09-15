@echo off
setlocal EnableExtensions

REM ============================================================
REM  调休管家 - 停止后端
REM
REM  与旧版的区别：
REM   旧版用 taskkill /FI "WINDOWTITLE eq tiaoxiu-backend" 按窗口标题找进程，
REM   这个办法不可靠：只要后端不是由 start.bat 开的那个窗口启动的（比如 VS Code
REM   终端、命令行 java -jar、IDE、计划任务等任何没有窗口的方式），就永远找不到
REM   目标，于是只会打印"未找到标题为 tiaoxiu-backend 的进程"。
REM
REM   本版改为：先按「监听 8600 端口的进程」定位并结束 —— 后端一定在监听这个
REM   端口，所以无论它是怎么启动的都能停掉，这是主要手段。结束之后再顺手按窗口
REM   标题清理 start.bat 留下的那个 cmd 窗口，这只是收尾，失败也不影响停止。
REM
REM   如果改过后端端口，请同步修改下面的 PORT。
REM  打印一次成功/失败结论，然后 3 秒自动关窗。
REM ============================================================

set "PORT=8600"
set "RC=0"
set "BUSY_PID="

call :PortPid %PORT%

if not defined BUSY_PID (
  echo [提示] 端口 %PORT% 上没有监听进程，后端当前并没有在运行（无需停止）。
  goto :DONE
)

echo 发现后端进程 PID=%BUSY_PID%（正在监听端口 %PORT%），准备停止...
taskkill /PID %BUSY_PID% /T /F >nul 2>&1

if errorlevel 1 (
  set "RC=1"
  echo [失败] 结束 PID=%BUSY_PID% 失败。
  echo         如果提示"拒绝访问"，请右键本脚本，选择"以管理员身份运行"。
  goto :DONE
)

echo 已发送结束指令，正在确认端口是否释放...

REM 二次确认端口已经释放（这是判断"是否真的停掉"的唯一依据）
call :PortPid %PORT%
if defined BUSY_PID (
  set "RC=1"
  echo [失败] 端口 %PORT% 仍被 PID=%BUSY_PID% 占用，未完全停止。
  goto :DONE
)

echo [停止成功] 后端已结束，端口 %PORT% 已释放。

REM 收尾清理：关掉 start.bat 留下的那个 cmd 窗口（尽力而为，失败不影响结论）
taskkill /FI "WINDOWTITLE eq tiaoxiu-backend" /T /F >nul 2>&1
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
