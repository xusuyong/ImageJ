@echo off
setlocal
set "IJ_DIR=%~dp0"
if exist "C:\Program Files\RedHat\java-1.8.0-openjdk-1.8.0.504-1\bin\javaw.exe" (
    set "JAVA_EXE=C:\Program Files\RedHat\java-1.8.0-openjdk-1.8.0.504-1\bin\javaw.exe"
) else (
    set "JAVA_EXE=javaw.exe"
)

start "" "%JAVA_EXE%" -Xmx16g -jar "%IJ_DIR%ij.jar" %*
endlocal
