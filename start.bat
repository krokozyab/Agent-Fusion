@echo off
REM ==============================================================================
REM Agent Fusion Startup Script - Windows
REM ==============================================================================
REM This script starts the Agent Fusion orchestrator server.
REM Usage:
REM   start.bat                                 - Use default fusionagent.toml
REM   start.bat -c fusionagent_win.toml        - Use custom config file
REM ==============================================================================

setlocal enabledelayedexpansion

REM Color codes using findstr (simulated colors)
REM Since native Windows console doesn't support ANSI colors easily,
REM we'll use text formatting instead

set CONFIG_FILE=fusionagent.toml
set JAR_FILE=build\libs\orchestrator-0.1.0-all.jar

REM Parse command line arguments
:parse_args
if "%1"=="" goto args_done
if "%1"=="-a" (
    set CONFIG_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--agents" (
    set CONFIG_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-j" (
    set JAR_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="--jar" (
    set JAR_FILE=%2
    shift
    shift
    goto parse_args
)
if "%1"=="-h" (
    goto show_help
)
if "%1"=="--help" (
    goto show_help
)
shift
goto parse_args

:show_help
echo Agent Fusion Startup Script
echo.
echo Usage: start.bat [OPTIONS]
echo.
echo Options:
echo   -a, --agents FILE    Path to config file (default: fusionagent.toml)
echo   -j, --jar FILE       Use custom JAR file (default: build\libs\orchestrator-0.1.0-all.jar)
echo   -h, --help           Show this help message
echo.
echo Examples:
echo   start.bat
echo   start.bat --agents fusionagent_win.toml
echo   start.bat --jar custom\path\orchestrator.jar
pause
exit /b 0

:args_done
cls
echo.
echo ======================================================================
echo           Agent Fusion - Orchestrator Server
echo ======================================================================
echo.

REM Check if Java is installed
echo Checking Java installation...
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java is not installed or not in PATH
    echo.
    echo Please install Java 21 or higher and add it to your PATH.
    echo Visit: https://www.java.com/download or https://adoptopenjdk.net/
    echo.
    pause
    exit /b 1
)
echo [OK] Java is installed
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /r "version"') do set JAVA_VERSION=%%i
echo      Version: %JAVA_VERSION%
echo.

REM Check if config file exists
echo Checking configuration file...
if not exist "%CONFIG_FILE%" (
    echo [ERROR] Configuration file not found: %CONFIG_FILE%
    echo.
    echo Available config files:
    dir /b fusionagent*.toml 2>nul || echo   (none found^)
    echo.
    pause
    exit /b 1
)
echo [OK] Config file found: %CONFIG_FILE%
echo.

REM Check if JAR file exists
echo Checking JAR file...
if not exist "%JAR_FILE%" (
    echo [ERROR] JAR file not found: %JAR_FILE%
    echo.
    echo To build the JAR file, run:
    echo   gradlew.bat shadowJar
    echo.
    pause
    exit /b 1
)
echo [OK] JAR file found: %JAR_FILE%
for %%A in ("%JAR_FILE%") do set JAR_SIZE=%%~zA
set /a JAR_SIZE_MB=JAR_SIZE / 1024 / 1024
echo      Size: %JAR_SIZE_MB% MB
echo.

REM Display startup information
echo Starting Agent Fusion...
echo.
echo ======================================================================
echo                      Access Points
echo ======================================================================
echo.
echo   MCP Server (Agent Connection):
echo     http://127.0.0.1:3000
echo.
echo   Web Dashboard:
echo     http://localhost:8081
echo.
echo   Press Ctrl+C to stop the server
echo.
echo ======================================================================
echo.

REM Run the JAR file
java -jar "%JAR_FILE%" --agents "%CONFIG_FILE%"

if errorlevel 1 (
    echo.
    echo [ERROR] Agent Fusion failed to start
    echo.
    pause
    exit /b 1
)

exit /b 0
