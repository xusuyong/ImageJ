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
if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

:: 3. Check if runtime (JRE) already exists in dist
if exist "%DIST_DIR%\jre" (
    echo [1/4] Reusing existing JRE and base resources in %DIST_DIR%
) else (
    echo [1/4] JRE not found in dist, searching for template resources...
    set "TEMPLATE_DIR="
    if defined IMAGEJ_TEMPLATE if exist "!IMAGEJ_TEMPLATE!\jre" set "TEMPLATE_DIR=!IMAGEJ_TEMPLATE!"
    if not defined TEMPLATE_DIR if exist "E:\software\ij154-win-java8\ImageJ\jre" set "TEMPLATE_DIR=E:\software\ij154-win-java8\ImageJ"
    if not defined TEMPLATE_DIR if exist "E:\software\ij154-win-java8\jre" set "TEMPLATE_DIR=E:\software\ij154-win-java8"
    if not defined TEMPLATE_DIR if exist "D:\xsy\othercode\ij154-win-java8\ImageJ\jre" set "TEMPLATE_DIR=D:\xsy\othercode\ij154-win-java8\ImageJ"
    if not defined TEMPLATE_DIR if exist "%~dp0..\ij154-win-java8\ImageJ\jre" set "TEMPLATE_DIR=%~dp0..\ij154-win-java8\ImageJ"
    if not defined TEMPLATE_DIR if exist "%~dp0..\ij154-win-java8\jre" set "TEMPLATE_DIR=%~dp0..\ij154-win-java8"

    if defined TEMPLATE_DIR (
        echo [INFO] Found template resources at: !TEMPLATE_DIR!
        echo [2/4] Copying runtime and plugins from template...
        if exist "!TEMPLATE_DIR!\jre" xcopy /E /I /Y "!TEMPLATE_DIR!\jre" "%DIST_DIR%\jre" >nul
        if exist "!TEMPLATE_DIR!\luts" xcopy /E /I /Y "!TEMPLATE_DIR!\luts" "%DIST_DIR%\luts" >nul
        if exist "!TEMPLATE_DIR!\macros" xcopy /E /I /Y "!TEMPLATE_DIR!\macros" "%DIST_DIR%\macros" >nul
        if exist "!TEMPLATE_DIR!\plugins" xcopy /E /I /Y "!TEMPLATE_DIR!\plugins" "%DIST_DIR%\plugins" >nul
    ) else (
        echo [NOTE] No JRE template found. ImageJ-CT will run in lightweight mode - using system Java.
        echo [TIP] To include bundled JRE, set IMAGEJ_TEMPLATE=path\to\ImageJ or place jre in dist\ImageJ-CT\jre
    )
)

:: 4. Build native Unicode launcher ImageJ-CT.exe
echo [3/4] Building native Unicode launcher ImageJ-CT.exe...
set "CSC_EXE=C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
if not exist "%CSC_EXE%" set "CSC_EXE=C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"

if exist "%CSC_EXE%" (
    "%CSC_EXE%" /nologo /target:winexe /win32icon:"%~dp0ImageJ.ico" /out:"%DIST_DIR%\ImageJ-CT.exe" "%~dp0ImageJLauncher.cs"
) else (
    echo [WARNING] csc.exe not found!
)

:: Configure launcher cfg
if exist "%DIST_DIR%\jre\bin\javaw.exe" (
    > "%DIST_DIR%\ImageJ-CT.cfg" echo .
    >> "%DIST_DIR%\ImageJ-CT.cfg" echo jre\bin\javaw.exe
    >> "%DIST_DIR%\ImageJ-CT.cfg" echo -Xmx8000m -cp ij.jar ij.ImageJ
) else (
    > "%DIST_DIR%\ImageJ-CT.cfg" echo .
    >> "%DIST_DIR%\ImageJ-CT.cfg" echo javaw.exe
    >> "%DIST_DIR%\ImageJ-CT.cfg" echo -Xmx8000m -cp ij.jar ij.ImageJ
)
copy /Y "%DIST_DIR%\ImageJ-CT.cfg" "%DIST_DIR%\ImageJ.cfg" >nul

:: 5. Install latest ij.jar
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
if exist "%DIST_DIR%\jre\bin\javaw.exe" (
    echo   Users can simply run ImageJ-CT.exe without installing Java!
) else (
    echo   Notice: Lightweight package without JRE - requires Java on user PC.
)
echo ========================================================

endlocal
