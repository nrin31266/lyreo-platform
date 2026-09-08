@echo off
setlocal
set MVNW_DIR=%~dp0
set MAVEN_VERSION=3.9.14
set MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  echo Maven wrapper bootstrap on Windows requires Maven or a manual download of Apache Maven %MAVEN_VERSION%.
  echo See README.md - Development setup.
  exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
