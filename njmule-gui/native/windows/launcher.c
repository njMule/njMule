// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// njmule-gui.exe, the desktop app on Windows. The app is njmule.exe beside it, a console program as its
// command line must be: this GUI program starts it without a console, marked for the desktop by
// NJMULE_DESKTOP=1, with the same arguments, then waits for it and returns its exit code, as javaw.exe
// stands for java.exe. Shortcuts, ed2k links, the start at login and the uninstaller run this one.
//
// A copy of it, out of the folder Windows Installer replaces, also installs a new version: njMule runs it
// as it stops, with
//     --apply-update <msi> --wait <pid>... --ui passive|quiet [--all-users] --log <file> --result <file>
//     [--relaunch <program> <arguments>...]
// It waits for those processes to end, runs msiexec on the package, writes its exit code to the result
// file, which the next start of njMule reads, and starts njMule again when asked.
//
// The uninstaller runs it with --uninstall-profile: it asks whether the user's profile goes too. njMule runs it with
// --keep-awake <pid> while files are transferred, if asked to keep the computer from sleeping.

#include <stdlib.h>
#include <stdio.h>
#include <wchar.h>
#include <windows.h>
#include <shellapi.h>
#include <commctrl.h>

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "shell32.lib")

// The common controls 6, for the task dialog of the uninstaller.
#pragma comment(linker, "\"/manifestdependency:type='win32' name='Microsoft.Windows.Common-Controls' version='6.0.0.0' processorArchitecture='*' publicKeyToken='6595b64144ccf1df' language='*'\"")

#define PROFILE_QUESTION L"Also delete your njMule data?"
#define PROFILE_TEXT \
    L"Your data is your settings, server and contact lists, identity and credits, unfinished downloads and logs. " \
    L"Leave the box unticked to keep it for a later installation; tick it to move the data to the Recycle Bin.\n\n" \
    L"Your completed downloads are always kept."
#define MAX_WAITS 8
#define WAIT_MILLIS 60000

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

// Appends arg to line as the C runtime and CommandLineToArgvW read it back: quoted when it holds a blank or
// a quote, its quotes and the backslashes before them escaped.
static void append_argument(wchar_t *line, size_t size, const wchar_t *arg) {
    size_t at = wcslen(line);
    if (at > 0) {
        line[at++] = L' ';
    }
    if (*arg != L'\0' && wcspbrk(arg, L" \t\"") == NULL) {
        wcscpy_s(line + at, size - at, arg);
        return;
    }
    line[at++] = L'"';
    for (const wchar_t *c = arg;; c++) {
        size_t backslashes = 0;
        while (*c == L'\\') {
            backslashes++;
            c++;
        }
        if (*c == L'\0') {
            // Doubled: the closing quote must stay a quote.
            for (size_t i = 0; i < backslashes * 2; i++) {
                line[at++] = L'\\';
            }
            break;
        }
        if (*c == L'"') {
            for (size_t i = 0; i < backslashes * 2 + 1; i++) {
                line[at++] = L'\\';
            }
        } else {
            for (size_t i = 0; i < backslashes; i++) {
                line[at++] = L'\\';
            }
        }
        line[at++] = *c;
    }
    line[at++] = L'"';
    line[at] = L'\0';
}

// Room for args quoted, each at worst every character escaped, a blank and two quotes.
static wchar_t *command_line(int count, wchar_t **args) {
    size_t size = 1;
    for (int i = 0; i < count; i++) {
        size += wcslen(args[i]) * 2 + 3;
    }
    wchar_t *line = calloc(size, sizeof *line);
    if (line != NULL) {
        for (int i = 0; i < count; i++) {
            append_argument(line, size, args[i]);
        }
    }
    return line;
}

static BOOL ends_with(const wchar_t *text, const wchar_t *end) {
    size_t length = wcslen(text);
    size_t tail = wcslen(end);
    return length >= tail && _wcsicmp(text + length - tail, end) == 0;
}

// Installs the package as the header says; returns 0 when Windows Installer succeeded (3010: it did, and a
// reboot would finish replacing files in use), else its exit code.
static int apply_update(int argc, wchar_t **argv) {
    const wchar_t *msi = NULL;
    const wchar_t *ui = L"passive";
    const wchar_t *log = NULL;
    const wchar_t *result = NULL;
    BOOL all_users = FALSE;
    DWORD waits[MAX_WAITS];
    int wait_count = 0;
    int relaunch = -1;
    for (int i = 1; i < argc; i++) {
        if (wcscmp(argv[i], L"--apply-update") == 0 && i + 1 < argc) {
            msi = argv[++i];
        } else if (wcscmp(argv[i], L"--wait") == 0 && i + 1 < argc) {
            DWORD pid = wcstoul(argv[++i], NULL, 10);
            if (pid != 0 && wait_count < MAX_WAITS) {
                waits[wait_count++] = pid;
            }
        } else if (wcscmp(argv[i], L"--ui") == 0 && i + 1 < argc) {
            ui = argv[++i];
        } else if (wcscmp(argv[i], L"--all-users") == 0) {
            all_users = TRUE;
        } else if (wcscmp(argv[i], L"--log") == 0 && i + 1 < argc) {
            log = argv[++i];
        } else if (wcscmp(argv[i], L"--result") == 0 && i + 1 < argc) {
            result = argv[++i];
        } else if (wcscmp(argv[i], L"--relaunch") == 0 && i + 1 < argc) {
            relaunch = i + 1;
            break;
        } else {
            fail(L"njMule cannot install the new version: the installer was started wrongly.");
            return 2;
        }
    }
    // The old njMule and the launcher that waits for it hold the files Windows Installer replaces.
    for (int i = 0; i < wait_count; i++) {
        HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, waits[i]);
        if (process != NULL) {
            WaitForSingleObject(process, WAIT_MILLIS);
            CloseHandle(process);
        }
    }
    static wchar_t msiexec[MAX_PATH];
    UINT length = GetSystemDirectoryW(msiexec, MAX_PATH);
    if (length == 0 || length + 13 >= MAX_PATH) {
        fail(L"njMule cannot find Windows Installer.");
        return 2;
    }
    wcscat_s(msiexec, MAX_PATH, L"\\msiexec.exe");
    wchar_t *install[10];
    int count = 0;
    install[count++] = msiexec;
    install[count++] = L"/i";
    install[count++] = (wchar_t *) msi;
    install[count++] = wcscmp(ui, L"quiet") == 0 ? L"/quiet" : L"/passive";
    install[count++] = L"/norestart";
    if (log != NULL) {
        install[count++] = L"/l*v";
        install[count++] = (wchar_t *) log;
    }
    wchar_t *line = command_line(count, install);
    if (line == NULL) {
        return 2;
    }
    // Installed for every user: the same installation, which Windows Installer asks the rights for.
    if (all_users) {
        size_t size = wcslen(line) + 24;
        wchar_t *longer = realloc(line, size * sizeof *line);
        if (longer == NULL) {
            free(line);
            return 2;
        }
        line = longer;
        wcscat_s(line, size, L" MSIINSTALLPERUSER=\"\"");
    }
    STARTUPINFOW startup = {0};
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process;
    DWORD code = 1603;
    if (CreateProcessW(msiexec, line, NULL, NULL, FALSE, 0, NULL, NULL, &startup, &process)) {
        CloseHandle(process.hThread);
        WaitForSingleObject(process.hProcess, INFINITE);
        GetExitCodeProcess(process.hProcess, &code);
        CloseHandle(process.hProcess);
    } else {
        code = GetLastError();
    }
    free(line);
    if (result != NULL) {
        FILE *out = NULL;
        if (_wfopen_s(&out, result, L"w") == 0 && out != NULL) {
            fprintf(out, "%lu\n", code);
            fclose(out);
        }
    }
    // The new version, or the old one when the installation failed, tells what happened.
    if (relaunch > 0) {
        wchar_t *again = command_line(argc - relaunch, argv + relaunch);
        STARTUPINFOW started = {0};
        started.cb = sizeof started;
        if (again != NULL
                && CreateProcessW(argv[relaunch], again, NULL, NULL, FALSE, 0, NULL, NULL, &started, &process)) {
            AllowSetForegroundWindow(process.dwProcessId);
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
        }
        free(again);
    }
    return code == 0 || code == ERROR_SUCCESS_REBOOT_REQUIRED ? 0 : (int) code;
}

// Appends the folder %variable%\njMule, if it exists, to a list of paths each ended by a null.
static void add_profile_folder(wchar_t *list, size_t size, size_t *at, const wchar_t *variable) {
    wchar_t folder[MAX_PATH];
    DWORD length = GetEnvironmentVariableW(variable, folder, MAX_PATH);
    if (length == 0 || length + 8 >= MAX_PATH || wcscat_s(folder, MAX_PATH, L"\\njMule") != 0
            || GetFileAttributesW(folder) == INVALID_FILE_ATTRIBUTES) {
        return;
    }
    size_t needed = wcslen(folder) + 1;
    if (*at + needed + 1 > size) {
        return;
    }
    wcscpy_s(list + *at, size - *at, folder);
    *at += needed;
}

typedef HRESULT(WINAPI *TaskDialogFunction)(const TASKDIALOGCONFIG *, int *, int *, BOOL *);

// Whether the user ticks the box that deletes the profile; unticked at first. The task dialog comes from the
// common controls 6 the manifest asks for, found at run time, so that the launcher starts without them; a yes or
// no question stands in for it then.
static BOOL profile_ticked(void) {
    HMODULE controls = LoadLibraryW(L"comctl32.dll");
    TaskDialogFunction dialog =
            controls == NULL ? NULL : (TaskDialogFunction) (void *) GetProcAddress(controls, "TaskDialogIndirect");
    if (dialog == NULL) {
        return MessageBoxW(NULL,
                       PROFILE_QUESTION L"\n\n" PROFILE_TEXT,
                       L"njMule", MB_YESNO | MB_ICONQUESTION | MB_DEFBUTTON2 | MB_SETFOREGROUND | MB_TOPMOST)
                == IDYES;
    }
    TASKDIALOGCONFIG config = {0};
    config.cbSize = sizeof config;
    config.dwFlags = TDF_ALLOW_DIALOG_CANCELLATION;
    config.pszWindowTitle = L"Uninstall njMule";
    config.pszMainIcon = TD_INFORMATION_ICON;
    config.pszMainInstruction = PROFILE_QUESTION;
    config.pszContent = PROFILE_TEXT;
    config.pszVerificationText = L"Delete my njMule data";
    config.dwCommonButtons = TDCBF_OK_BUTTON;
    int button = 0;
    BOOL ticked = FALSE;
    return SUCCEEDED(dialog(&config, &button, NULL, &ticked)) && button == IDOK && ticked;
}

// At uninstall: lets the user choose to delete their profile of njMule too, moved to the Recycle Bin. The
// completed downloads, in the Downloads folder, stay.
static int uninstall_profile(void) {
    static wchar_t folders[2 * MAX_PATH + 2];
    size_t at = 0;
    add_profile_folder(folders, ARRAYSIZE(folders), &at, L"APPDATA");
    add_profile_folder(folders, ARRAYSIZE(folders), &at, L"LOCALAPPDATA");
    if (at == 0) {
        return 0;
    }
    folders[at] = L'\0';
    if (!profile_ticked()) {
        return 0;
    }
    SHFILEOPSTRUCTW operation = {0};
    operation.wFunc = FO_DELETE;
    operation.pFrom = folders;
    operation.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_SILENT | FOF_NOERRORUI;
    return SHFileOperationW(&operation);
}

// Keeps the computer from sleeping until the njMule running as pid ends, or ends this program.
static int keep_awake(DWORD pid) {
    HANDLE process = OpenProcess(SYNCHRONIZE, FALSE, pid);
    if (process == NULL) {
        return 1;
    }
    SetThreadExecutionState(ES_CONTINUOUS | ES_SYSTEM_REQUIRED);
    WaitForSingleObject(process, INFINITE);
    CloseHandle(process);
    return 0;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void) instance;
    (void) previous;
    (void) commandLine;
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv != NULL && argc > 2 && wcscmp(argv[1], L"--apply-update") == 0 && ends_with(argv[2], L".msi")) {
        int code = apply_update(argc, argv);
        LocalFree(argv);
        return code;
    }
    if (argv != NULL && argc == 2 && wcscmp(argv[1], L"--uninstall-profile") == 0) {
        LocalFree(argv);
        return uninstall_profile();
    }
    if (argv != NULL && argc == 3 && wcscmp(argv[1], L"--keep-awake") == 0) {
        DWORD pid = wcstoul(argv[2], NULL, 10);
        LocalFree(argv);
        return keep_awake(pid);
    }
    if (argv != NULL) {
        LocalFree(argv);
    }
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
