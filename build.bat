@echo off
setlocal
echo Compiling ImageJ source files...
javac -encoding utf-8 ij\ImageJ.java ij\plugin\*.java ij\plugin\filter\*.java ij\plugin\frame\*.java
if %ERRORLEVEL% neq 0 (
    echo Compilation failed!
    exit /b %ERRORLEVEL%
)

echo Packaging ij.jar...
if not exist build mkdir build
xcopy /E /I /Y ij build\ij >nul
copy /Y IJ_Props.txt build\ >nul
copy /Y images\microscope.gif build\microscope.gif >nul
copy /Y images\about.jpg build\about.jpg >nul
xcopy /E /I /Y macros build\macros >nul
if exist plugins\MacAdapter.class copy /Y plugins\MacAdapter.class build\ij\plugin\ >nul
if exist plugins\MacAdapter9.class copy /Y plugins\MacAdapter9.class build\ij\plugin\ >nul

jar cvfm ij.jar MANIFEST.MF -C build . >nul
echo Build completed successfully! ij.jar is ready.
endlocal
