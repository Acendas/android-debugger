@echo off
rem Cross-platform wrapper for Windows. Invokes the Node CLI alongside this .cmd.
setlocal
set "BIN_DIR=%~dp0"
node "%BIN_DIR%android-debugger-classdiff.mjs" %*
endlocal
exit /b %ERRORLEVEL%
