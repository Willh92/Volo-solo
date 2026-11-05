@echo off
setlocal
cd /d "%~dp0"
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_OPTS set "JAVA_OPTS=-Xms128m -Xmx384m -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:+UseStringDeduplication -XX:MaxDirectMemorySize=96m -Djava.awt.headless=true -Dio.netty.allocator.maxOrder=9 -Dio.netty.allocator.numDirectArenas=2 -Dio.netty.allocator.numHeapArenas=2"
"%JAVA%" %JAVA_OPTS% -Dfile.encoding=UTF-8 -cp "WEB-INF/classes;WEB-INF/lib/*;." org.b3log.solo.Starter %*
exit /b %errorlevel%
