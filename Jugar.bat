@echo off
REM Lanzador de Scoundrel.
REM Asume que la carpeta con las imagenes (Dungeon.png, "2 de picas.png", etc.)
REM esta UN nivel por encima de este script (la carpeta "Juego cartas").
setlocal
set IMG_DIR=%~dp0..
where java >nul 2>nul
if errorlevel 1 (
    echo No se encontro Java. Instala JRE 8 o superior y vuelve a intentarlo.
    pause
    exit /b 1
)
java -jar "%~dp0Scoundrel.jar" "%IMG_DIR%"
endlocal
