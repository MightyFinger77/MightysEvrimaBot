@echo off
cd /d "%~dp0"

if not exist "evrima-server-bot-1.0.1.jar" (
    echo [ERROR] evrima-server-bot-1.0.1.jar not found in this folder.
    echo Put start-bot.bat next to the shaded JAR from mvn package.
    pause
    exit /b 1
)

for %%I in ("evrima-server-bot-1.0.1.jar") do set "JARSIZE=%%~zI"
if %JARSIZE% LSS 5000000 (
    echo [ERROR] This JAR is only %JARSIZE% bytes — not the runnable "fat" build.
    echo.
    echo Run from the project folder:  mvn -q package
    echo Then copy THIS file to EvrimaBot ^(not "original-evrima-server-bot-1.0.1.jar"^):
    echo   target\evrima-server-bot-1.0.1.jar   ^(~35 MB with dependencies^)
    pause
    exit /b 1
)

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java not on PATH. Install Java 17+ ^(Temurin, Oracle, etc.^).
    pause
    exit /b 1
)

echo Starting EvrimaServerBot...
echo Working directory: %CD%
echo.

REM JDK 24+: SQLite JDBC loads a native library; this flag silences "restricted method" warnings.
set "JVM_EXTRA="
java --enable-native-access=ALL-UNNAMED -version >nul 2>&1
if not errorlevel 1 set "JVM_EXTRA=--enable-native-access=ALL-UNNAMED"

java %JVM_EXTRA% -jar "evrima-server-bot-1.0.1.jar"
set "ERR=%ERRORLEVEL%"

echo.
if not "%ERR%"=="0" (
    echo Exited with code %ERR%.
    pause
)

exit /b %ERR%
