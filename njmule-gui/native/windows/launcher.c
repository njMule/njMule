// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// njmule-gui.exe, the desktop app on Windows. The app is njmule.exe beside it, a console program as its
// command line must be: this GUI program starts it without a console, marked for the desktop by
// NJMULE_DESKTOP=1, with the same arguments, then waits for it and returns its exit code, as javaw.exe
// stands for java.exe. Shortcuts, ed2k links, the start at login and the uninstaller run this one.

#include <stdlib.h>
#include <wchar.h>
#include <windows.h>

#pragma comment(lib, "user32.lib")

static void fail(const wchar_t *message) {
    MessageBoxW(NULL, message, L"njMule", MB_OK | MB_ICONERROR);
}

// The arguments after the program's own name, as typed. The name has no escapes: it is quoted up to the
// next quote, or ends at the first blank.
static const wchar_t *arguments(const wchar_t *line) {
    if (*line == L'"') {
        line++;
        while (*line != L'\0' && *line != L'"') {
            line++;
        }
        if (*line == L'"') {
            line++;
        }
    } else {
        while (*line != L'\0' && *line != L' ' && *line != L'\t') {
            line++;
        }
    }
    while (*line == L' ' || *line == L'\t') {
        line++;
    }
    return line;
}

// A standard handle the app can inherit, such as a file or pipe a command line redirected to; NULL when
// there is none, as for a GUI program started from Explorer.
static HANDLE inheritable(DWORD which) {
    HANDLE handle = GetStdHandle(which);
    if (handle == NULL || handle == INVALID_HANDLE_VALUE
            || !SetHandleInformation(handle, HANDLE_FLAG_INHERIT, HANDLE_FLAG_INHERIT)) {
        return NULL;
    }
    return handle;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void) instance;
    (void) previous;
    (void) commandLine;
    static wchar_t app[32768];
    DWORD length = GetModuleFileNameW(NULL, app, ARRAYSIZE(app));
    wchar_t *name = wcsrchr(app, L'\\');
    if (length == 0 || length >= ARRAYSIZE(app) || name == NULL
            || wcscpy_s(name + 1, ARRAYSIZE(app) - (size_t) (name + 1 - app), L"njmule.exe") != 0) {
        fail(L"njMule cannot find its own folder.");
        return 1;
    }
    const wchar_t *args = arguments(GetCommandLineW());
    size_t size = wcslen(app) + wcslen(args) + 4;
    wchar_t *line = malloc(size * sizeof *line);
    if (line == NULL || swprintf(line, size, L"\"%ls\" %ls", app, args) < 0) {
        fail(L"The command line is too long.");
        return 1;
    }
    STARTUPINFOW startup = {0};
    startup.cb = sizeof startup;
    startup.dwFlags = STARTF_USESHOWWINDOW;
    startup.wShowWindow = (WORD) show;
    startup.hStdInput = inheritable(STD_INPUT_HANDLE);
    startup.hStdOutput = inheritable(STD_OUTPUT_HANDLE);
    startup.hStdError = inheritable(STD_ERROR_HANDLE);
    BOOL redirected = startup.hStdInput != NULL || startup.hStdOutput != NULL || startup.hStdError != NULL;
    if (redirected) {
        startup.dwFlags |= STARTF_USESTDHANDLES;
    }
    SetEnvironmentVariableW(L"NJMULE_DESKTOP", L"1");
    PROCESS_INFORMATION process;
    if (!CreateProcessW(app, line, NULL, NULL, redirected, DETACHED_PROCESS, NULL, NULL, &startup, &process)) {
        wchar_t message[MAX_PATH + 64];
        swprintf(message, ARRAYSIZE(message), L"njMule cannot start njmule.exe (error %lu).", GetLastError());
        fail(message);
        return 1;
    }
    // The window comes to the front as if this program had opened it.
    AllowSetForegroundWindow(process.dwProcessId);
    CloseHandle(process.hThread);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD code = 1;
    GetExitCodeProcess(process.hProcess, &code);
    return (int) code;
}
