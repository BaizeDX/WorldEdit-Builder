@echo off
setlocal
cd /d "%~dp0"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.11"
set "GRADLE_USER_HOME=%CD%\.gradle-home"
set "_JAVA_OPTIONS=-Xmx1024m -Xms64m -XX:ActiveProcessorCount=4"
call gradlew.bat --max-workers=1 clean build
exit /b %errorlevel%
