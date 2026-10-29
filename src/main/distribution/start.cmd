@echo off
setlocal
cd /d "%~dp0"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
"%JAVA%" -Dfile.encoding=UTF-8 -cp "WEB-INF/classes;WEB-INF/lib/*;." org.b3log.solo.Starter %*
exit /b %errorlevel%
