@echo off
REM Recompila Scoundrel.jar a partir del codigo fuente.
setlocal
where javac >nul 2>nul
if errorlevel 1 (
    echo No se encontro javac. Instala el JDK ^(no solo el JRE^).
    pause
    exit /b 1
)
if not exist "%~dp0out" mkdir "%~dp0out"
javac -d "%~dp0out" "%~dp0src\scoundrel\Scoundrel.java"
if errorlevel 1 (
    echo Error de compilacion.
    pause
    exit /b 1
)
echo Main-Class: scoundrel.Scoundrel> "%~dp0out\manifest.txt"
pushd "%~dp0out"
jar cfm "%~dp0Scoundrel.jar" manifest.txt scoundrel\*.class
popd
echo OK -> Scoundrel.jar
endlocal
