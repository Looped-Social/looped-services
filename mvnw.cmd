@ECHO OFF
SETLOCAL

SET BASE_DIR=%~dp0
SET WRAPPER_JAR=%BASE_DIR%\.mvn\wrapper\maven-wrapper.jar
SET PROPS_FILE=%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties

IF NOT EXIST "%PROPS_FILE%" (
  ECHO [mvnw] Missing %PROPS_FILE%
  EXIT /B 1
)

FOR /F "usebackq tokens=1,* delims==" %%A IN ("%PROPS_FILE%") DO (
  IF "%%A"=="wrapperUrl" SET WRAPPER_URL=%%B
)

IF NOT EXIST "%WRAPPER_JAR%" (
  ECHO [mvnw] Downloading Maven Wrapper JAR...
  MKDIR "%BASE_DIR%\.mvn\wrapper" 2>NUL
  POWERSHELL -NoProfile -Command "^$u='%WRAPPER_URL%'; ^$o='%WRAPPER_JAR%'; (New-Object Net.WebClient).DownloadFile(^$u,^$o)" || (
    ECHO [mvnw] Failed to download wrapper jar
    EXIT /B 1
  )
)

IF DEFINED JAVA_HOME (
  SET JAVA_CMD=%JAVA_HOME%\bin\java.exe
) ELSE (
  SET JAVA_CMD=java.exe
)

"%JAVA_CMD%" -Dmaven.multiModuleProjectDirectory="%BASE_DIR%" -cp "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*

ENDLOCAL

