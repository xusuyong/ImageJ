@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================================
echo   Building and Packaging ImageJ Distribution for Users
echo ========================================================

:: 1. Compile latest code into ij.jar
call build.bat
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed!
    pause
    exit /b %ERRORLEVEL%
)

:: 2. Target output directory
set "DIST_DIR=%~dp0dist\ImageJ"
set "TEMPLATE_DIR=D:\xsy\othercode\ij154-win-java8\ImageJ"

if not exist "%TEMPLATE_DIR%" (
    echo [ERROR] Template ImageJ folder not found at: %TEMPLATE_DIR%
    pause
    exit /b 1
)

echo.
echo [1/3] Creating distribution directory: %DIST_DIR%
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"

echo [2/3] Copying runtime and launcher from template...
xcopy /E /I /Y "%TEMPLATE_DIR%\jre" "%DIST_DIR%\jre" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\luts" "%DIST_DIR%\luts" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\macros" "%DIST_DIR%\macros" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\plugins" "%DIST_DIR%\plugins" >nul
copy /Y "%TEMPLATE_DIR%\ImageJ.exe" "%DIST_DIR%\" >nul

echo [3/3] Installing customized ij.jar...
copy /Y "%~dp0ij.jar" "%DIST_DIR%\ij.jar" >nul

echo.
echo ========================================================
echo   Distribution Package Ready!
echo   Location: %DIST_DIR%
echo ========================================================
echo   You can compress or copy the 'dist\ImageJ' folder
echo   and distribute it directly to end users.
echo   Users can simply run ImageJ.exe without installing Java!
echo ========================================================

endlocal
