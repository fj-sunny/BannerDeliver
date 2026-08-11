@echo off
setlocal
set "MAVEN_HOME=%~dp0.tools\apache-maven-3.9.11"
call "%MAVEN_HOME%\bin\mvn.cmd" %*
