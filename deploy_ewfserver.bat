@echo off
set SRC=C:\cc_eone\eWF\ewfserver\target\ewfserver
set DST=C:\CC_20261019_eWF\server\tomcat\webapps\ewfserver

if not exist "%SRC%\WEB-INF\classes" (
  echo Build non trovata in %SRC%: fare prima Maven install di ewfserver.
  pause
  exit /b 1
)
echo Copio la build in Tomcat: %DST%

rem toglie la vecchia applicazione (cartella, o file rimasto per errore)
if exist "%DST%\" rmdir /s /q "%DST%"
if exist "%DST%" del /f /q "%DST%"

xcopy /E /I /Y /Q "%SRC%" "%DST%\"
echo.
echo Controllo:
dir /b "%DST%\WEB-INF\classes\it\eone\ewf\ewfserver\app"
pause