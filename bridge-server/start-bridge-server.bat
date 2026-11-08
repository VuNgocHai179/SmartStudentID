@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
echo Stopping any old BridgeServer instance on port 8080...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080" ^| findstr "LISTENING"') do taskkill /F /PID %%a >nul 2>&1

echo Setting up USB reverse forwarding (adb reverse tcp:8080 tcp:8080)...
if exist "E:\AndroidSDK\platform-tools\adb.exe" (
    "E:\AndroidSDK\platform-tools\adb.exe" reverse tcp:8080 tcp:8080 >nul 2>&1
) else (
    adb reverse tcp:8080 tcp:8080 >nul 2>&1
)

echo Compiling and starting Smart Student ID PC Bridge & SQLite Database Server...
javac -encoding UTF-8 --add-modules java.smartcardio,jdk.httpserver,java.sql -cp "bridge-server\lib\*" bridge-server\src\DatabaseManager.java bridge-server\src\BridgeServer.java -d bridge-server\out
if errorlevel 1 (
    echo [ERROR] BridgeServer compilation failed. Fix the errors above before starting the server.
    pause
    exit /b 1
)
java -Dfile.encoding=UTF-8 --add-modules java.smartcardio,jdk.httpserver,java.sql -cp "bridge-server\out;bridge-server\lib\*" BridgeServer
pause
