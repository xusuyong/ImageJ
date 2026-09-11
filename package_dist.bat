@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0"

echo ========================================================
echo   Building and Packaging ImageJ-CT Distribution for Users
echo ========================================================

:: 1. Compile latest code into ij.jar
call build.bat
if %ERRORLEVEL% neq 0 (
    echo [ERROR] Build failed!
    pause
    exit /b %ERRORLEVEL%
)

:: 2. Target output directory
set "DIST_DIR=%~dp0dist\ImageJ-CT"
set "TEMPLATE_DIR=D:\xsy\othercode\ij154-win-java8\ImageJ"

if not exist "%TEMPLATE_DIR%" (
    echo [ERROR] Template ImageJ folder not found at: %TEMPLATE_DIR%
    pause
    exit /b 1
)

echo.
echo [1/4] Creating distribution directory: %DIST_DIR%
if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
mkdir "%DIST_DIR%"

echo [2/4] Copying runtime and plugins from template...
xcopy /E /I /Y "%TEMPLATE_DIR%\jre" "%DIST_DIR%\jre" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\luts" "%DIST_DIR%\luts" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\macros" "%DIST_DIR%\macros" >nul
xcopy /E /I /Y "%TEMPLATE_DIR%\plugins" "%DIST_DIR%\plugins" >nul

echo [3/4] Setting up ImageJ-CT.exe and launcher configuration...
copy /Y "%TEMPLATE_DIR%\ImageJ.exe" "%DIST_DIR%\ImageJ-CT.exe" >nul
if exist "%TEMPLATE_DIR%\ImageJ.cfg" (
    copy /Y "%TEMPLATE_DIR%\ImageJ.cfg" "%DIST_DIR%\ImageJ-CT.cfg" >nul
    copy /Y "%TEMPLATE_DIR%\ImageJ.cfg" "%DIST_DIR%\ImageJ.cfg" >nul
) else (
    (
        echo .
        echo jre\bin\javaw.exe
        echo -Xmx8000m -cp ij.jar ij.ImageJ
    ) > "%DIST_DIR%\ImageJ-CT.cfg"
)

echo [4/4] Installing customized ij.jar...
copy /Y "%~dp0ij.jar" "%DIST_DIR%\ij.jar" >nul

:: Clean up obsolete dist\ImageJ if it exists
if exist "%~dp0dist\ImageJ" (
    echo Cleaning up previous dist\ImageJ...
    rmdir /s /q "%~dp0dist\ImageJ"
)

echo.
echo ========================================================
echo   Distribution Package Ready!
echo   Location: %DIST_DIR%
echo ========================================================
echo   You can compress or copy the 'dist\ImageJ-CT' folder
echo   and distribute it directly to end users.
echo   Users can simply run ImageJ-CT.exe without installing Java!
echo ========================================================

endlocal
