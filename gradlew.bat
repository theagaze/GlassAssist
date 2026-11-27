@echo off
setlocal EnableExtensions
set JAVA_HOME=C:\Users\gv\android-build\jdk17
set ANDROID_HOME=C:\Users\gv\android-build\sdk
set PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%PATH%
call "C:\Users\gv\android-build\gradle\gradle-7.6.4\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
