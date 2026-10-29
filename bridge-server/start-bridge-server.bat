@echo off
chcp 65001 >nul
cd /d "%~dp0\.."
echo Compiling and starting Smart Student ID PC Bridge Server...
javac -encoding UTF-8 --add-modules java.smartcardio,jdk.httpserver bridge-server\src\BridgeServer.java -d bridge-server\out
java --add-modules java.smartcardio,jdk.httpserver -cp bridge-server\out BridgeServer
pause
