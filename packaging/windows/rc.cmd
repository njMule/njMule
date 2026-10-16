@echo off
rem SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
rem SPDX-License-Identifier: GPL-3.0-or-later
rem
rem Compiles the resources of the Windows executable with rc.exe of the newest Windows SDK:
rem   rc.cmd <file.rc> <file.res> <version>
rem The numbers of the version (1.2.3 of 1.2.3-beta.1) become NJ_MAJOR, NJ_MINOR and NJ_PATCH.
setlocal
set "KITS=%ProgramFiles(x86)%\Windows Kits\10\bin"
set "RC="
for /f "delims=" %%v in ('dir /b /ad /o-n "%KITS%\10.*" 2^>nul') do (
    if exist "%KITS%\%%v\x64\rc.exe" (
        set "RC=%KITS%\%%v\x64\rc.exe"
        goto found
    )
    if exist "%KITS%\%%v\arm64\rc.exe" (
        set "RC=%KITS%\%%v\arm64\rc.exe"
        goto found
    )
)
echo rc.cmd: no rc.exe of a Windows SDK under "%KITS%" 1>&2
exit /b 1

:found
for /f "tokens=1-3 delims=.-+" %%a in ("%~3") do (
    set "MAJOR=%%a"
    set "MINOR=%%b"
    set "PATCH=%%c"
)
"%RC%" /nologo /d NJ_MAJOR=%MAJOR% /d NJ_MINOR=%MINOR% /d NJ_PATCH=%PATCH% /fo "%~2" "%~1"
