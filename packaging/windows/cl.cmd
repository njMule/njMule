@echo off
rem SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
rem SPDX-License-Identifier: GPL-3.0-or-later
rem
rem Builds a small GUI program for x64 with the newest Visual C++ tools, the C runtime linked statically:
rem   cl.cmd <program.exe> <source.c> <resources.res>
setlocal
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
set "VS="
if not exist "%VSWHERE%" goto missing
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS=%%i"
if not defined VS goto missing
call "%VS%\VC\Auxiliary\Build\vcvars64.bat" >nul 2>&1 || exit /b 1
cl /nologo /W4 /WX /O1 /MT /DUNICODE /D_UNICODE "/Fe%~1" "%~2" "%~3" /link /SUBSYSTEM:WINDOWS /MANIFEST:EMBED
exit /b %errorlevel%

:missing
echo cl.cmd: no Visual C++ tools for x64 1>&2
exit /b 1
