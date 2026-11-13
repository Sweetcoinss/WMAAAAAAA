@echo off
REM Collecte le journal du telephone (ADB) pour diagnostiquer un redemarrage.
REM Usage : tools\collect-log.bat
REM
REM 1. Brancher le telephone en USB et activer "Debogage USB" dans les options
REM    du developpeur.
REM 2. Lancer ce script, ouvrir l'application sur le telephone, puis
REM    revenir ici et appuyer sur une touche.

echo === Verification d'adb ===
where adb >nul 2>&1
if errorlevel 1 (
    echo adb introuvable.
    echo - Windows : telecharger le "SDK Platform-Tools" depuis
    echo   https://developer.android.com/tools/releases/platform-tools
    echo   puis ajouter le dossier au PATH.
    echo - Sinon : ouvrir une invite de commande dans le dossier des
    echo   platform-tools et lancer : adb devices
    exit /b 1
)

echo.
echo === Appareils connectes ===
adb devices -l
echo.

echo === Demarrage de la collecte (10 minutes) ===
echo Ouvre l'application sur le telephone maintenant.
echo.
adb logcat -c
adb logcat -v threadtime -s OpenSWAT4:V AndroidRuntime:E ActivityManager:E libc:E DEBUG:E Kernel:E "*:S" > swat4-log.txt
echo (la collecte s'arrete apres 10 minutes ; Ctrl+C pour l'interrompre)
