// SPDX-FileCopyrightText: 2026 Salvatore Coppola and the njMule contributors
// SPDX-License-Identifier: GPL-3.0-or-later
//
// njmule-service.exe, the Windows service "njMule": Windows starts it, it runs njmule.exe beside it without a
// window as the headless engine on the profile in %ProgramData%\njMule, under the virtual account the service
// was installed with (njmule --service=install). Stopped, it asks njMule to save and exit, as
// "njmule --exit" does, and ends it only if it has not after a while. When njMule ends on its own with an
// error, the service stops with that code, so that Windows restarts it as its recovery settings say.
//
// njmule --service=... (with the administrator's rights) also runs it with one argument to register the
// service: install, uninstall, start or stop return 0 or the Windows error; status returns the service's state
// (SERVICE_RUNNING and so on) or ERROR_SERVICE_DOES_NOT_EXIST.

#include <stdlib.h>
#include <wchar.h>
#include <windows.h>
#include <shlobj.h>

#pragma comment(lib, "advapi32.lib")
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")
#pragma comment(lib, "user32.lib")
#pragma comment(lib, "uuid.lib")

#define SERVICE_NAME L"njMule"
#define START_WAIT_MILLIS 10000
#define STOP_WAIT_MILLIS 60000
#define STEP_MILLIS 2000
#define RESTART_MILLIS 60000
#define FAILURE_RESET_SECONDS 86400
#define PRESHUTDOWN_MILLIS 90000

static SERVICE_STATUS_HANDLE status_handle;
static HANDLE stop_event;
static wchar_t app[32768];
static wchar_t profile[MAX_PATH];

static void report(DWORD state, DWORD exit_code, DWORD wait_hint) {
    static DWORD checkpoint = 1;
    SERVICE_STATUS status = {0};
    status.dwServiceType = SERVICE_WIN32_OWN_PROCESS;
    status.dwCurrentState = state;
    status.dwControlsAccepted = state == SERVICE_RUNNING ? SERVICE_ACCEPT_STOP | SERVICE_ACCEPT_PRESHUTDOWN : 0;
    if (exit_code == 0) {
        status.dwWin32ExitCode = NO_ERROR;
    } else {
        status.dwWin32ExitCode = ERROR_SERVICE_SPECIFIC_ERROR;
        status.dwServiceSpecificExitCode = exit_code;
    }
    status.dwWaitHint = wait_hint;
    status.dwCheckPoint = state == SERVICE_RUNNING || state == SERVICE_STOPPED ? 0 : checkpoint++;
    SetServiceStatus(status_handle, &status);
}

static DWORD WINAPI control(DWORD code, DWORD type, LPVOID data, LPVOID context) {
    (void) type;
    (void) data;
    (void) context;
    switch (code) {
        case SERVICE_CONTROL_STOP:
        case SERVICE_CONTROL_PRESHUTDOWN:
            report(SERVICE_STOP_PENDING, 0, STOP_WAIT_MILLIS);
            SetEvent(stop_event);
            return NO_ERROR;
        case SERVICE_CONTROL_INTERROGATE:
            return NO_ERROR;
        default:
            return ERROR_CALL_NOT_IMPLEMENTED;
    }
}

// Starts njmule.exe with "<first> --config-dir <profile>", without a window; in job, when given, which ends
// it should this program end first. Returns the process, or NULL.
static HANDLE start(const wchar_t *first, HANDLE job) {
    size_t size = wcslen(app) + wcslen(first) + wcslen(profile) + 32;
    wchar_t *line = malloc(size * sizeof *line);
    if (line == NULL || swprintf(line, size, L"\"%ls\" %ls --config-dir \"%ls\"", app, first, profile) < 0) {
        free(line);
        return NULL;
    }
    STARTUPINFOW startup = {0};
    startup.cb = sizeof startup;
    PROCESS_INFORMATION process;
    BOOL started = CreateProcessW(app, line, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, profile, &startup, &process);
    free(line);
    if (!started) {
        return NULL;
    }
    if (job != NULL) {
        AssignProcessToJobObject(job, process.hProcess);
    }
    CloseHandle(process.hThread);
    return process.hProcess;
}

static void WINAPI service_main(DWORD argc, LPWSTR *argv) {
    (void) argc;
    (void) argv;
    status_handle = RegisterServiceCtrlHandlerExW(SERVICE_NAME, control, NULL);
    if (status_handle == NULL) {
        return;
    }
    report(SERVICE_START_PENDING, 0, START_WAIT_MILLIS);
    stop_event = CreateEventW(NULL, TRUE, FALSE, NULL);
    HANDLE job = CreateJobObjectW(NULL, NULL);
    if (job != NULL) {
        JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits = {0};
        limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof limits);
    }
    HANDLE engine = stop_event == NULL ? NULL : start(L"--headless", job);
    if (engine == NULL) {
        report(SERVICE_STOPPED, GetLastError() == 0 ? 1 : GetLastError(), 0);
        return;
    }
    report(SERVICE_RUNNING, 0, 0);
    HANDLE waits[2] = {stop_event, engine};
    DWORD code = 0;
    if (WaitForMultipleObjects(2, waits, FALSE, INFINITE) == WAIT_OBJECT_0) {
        HANDLE asking = start(L"--exit", NULL);
        DWORD waited = 0;
        while (WaitForSingleObject(engine, STEP_MILLIS) == WAIT_TIMEOUT) {
            waited += STEP_MILLIS;
            if (waited >= STOP_WAIT_MILLIS) {
                TerminateProcess(engine, 1);
                WaitForSingleObject(engine, STEP_MILLIS);
                break;
            }
            report(SERVICE_STOP_PENDING, 0, STOP_WAIT_MILLIS - waited);
        }
        if (asking != NULL) {
            CloseHandle(asking);
        }
    } else {
        // Ended by itself: 0 when asked to (from the web interface), else an error Windows may restart on.
        GetExitCodeProcess(engine, &code);
    }
    CloseHandle(engine);
    if (job != NULL) {
        CloseHandle(job);
    }
    report(SERVICE_STOPPED, code, 0);
}

// Waits until the service is in state, as long as it moves towards it; returns whether it got there.
static BOOL await_state(SC_HANDLE service, DWORD state) {
    SERVICE_STATUS status;
    for (DWORD waited = 0; waited <= PRESHUTDOWN_MILLIS; waited += STEP_MILLIS) {
        if (!QueryServiceStatus(service, &status)) {
            return FALSE;
        }
        if (status.dwCurrentState == state) {
            return TRUE;
        }
        Sleep(STEP_MILLIS);
    }
    return FALSE;
}

// The service as this program, under the virtual account NT SERVICE\njMule, started with Windows after the
// others, restarted twice a minute after a failure, with a while to save at shutdown.
static DWORD install(void) {
    static wchar_t self[32768 + 2];
    wchar_t path[32768];
    DWORD length = GetModuleFileNameW(NULL, path, ARRAYSIZE(path));
    // Quoted: a path with blanks unquoted would let Windows try its parts as programs.
    if (length == 0 || length >= ARRAYSIZE(path) || swprintf(self, ARRAYSIZE(self), L"\"%ls\"", path) < 0) {
        return ERROR_BAD_PATHNAME;
    }
    SC_HANDLE manager = OpenSCManagerW(NULL, NULL, SC_MANAGER_CREATE_SERVICE | SC_MANAGER_CONNECT);
    if (manager == NULL) {
        return GetLastError();
    }
    const wchar_t *account = L"NT SERVICE\\" SERVICE_NAME;
    SC_HANDLE service = CreateServiceW(manager, SERVICE_NAME, SERVICE_NAME, SERVICE_ALL_ACCESS,
            SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START, SERVICE_ERROR_NORMAL, self, NULL, NULL, NULL, account,
            NULL);
    if (service == NULL && GetLastError() == ERROR_SERVICE_EXISTS) {
        service = OpenServiceW(manager, SERVICE_NAME, SERVICE_ALL_ACCESS);
        if (service != NULL && !ChangeServiceConfigW(service, SERVICE_WIN32_OWN_PROCESS, SERVICE_AUTO_START,
                SERVICE_ERROR_NORMAL, self, NULL, NULL, NULL, account, NULL, SERVICE_NAME)) {
            DWORD error = GetLastError();
            CloseServiceHandle(service);
            CloseServiceHandle(manager);
            return error;
        }
    }
    if (service == NULL) {
        DWORD error = GetLastError();
        CloseServiceHandle(manager);
        return error;
    }
    SERVICE_DESCRIPTIONW description = {
        L"eD2K and Kad file sharing: the njMule engine with its web interface, on the profile in "
        L"%ProgramData%\\njMule."
    };
    SERVICE_DELAYED_AUTO_START_INFO delayed = {TRUE};
    SC_ACTION actions[3] = {{SC_ACTION_RESTART, RESTART_MILLIS}, {SC_ACTION_RESTART, RESTART_MILLIS}, {SC_ACTION_NONE, 0}};
    SERVICE_FAILURE_ACTIONSW failure = {FAILURE_RESET_SECONDS, NULL, NULL, ARRAYSIZE(actions), actions};
    // An exit with an error counts as a failure too, not only a crash.
    SERVICE_FAILURE_ACTIONS_FLAG flag = {TRUE};
    SERVICE_SID_INFO sid = {SERVICE_SID_TYPE_UNRESTRICTED};
    SERVICE_PRESHUTDOWN_INFO preshutdown = {PRESHUTDOWN_MILLIS};
    DWORD error = NO_ERROR;
    if (!ChangeServiceConfig2W(service, SERVICE_CONFIG_DESCRIPTION, &description)
            || !ChangeServiceConfig2W(service, SERVICE_CONFIG_DELAYED_AUTO_START_INFO, &delayed)
            || !ChangeServiceConfig2W(service, SERVICE_CONFIG_FAILURE_ACTIONS, &failure)
            || !ChangeServiceConfig2W(service, SERVICE_CONFIG_FAILURE_ACTIONS_FLAG, &flag)
            || !ChangeServiceConfig2W(service, SERVICE_CONFIG_SERVICE_SID_INFO, &sid)
            || !ChangeServiceConfig2W(service, SERVICE_CONFIG_PRESHUTDOWN_INFO, &preshutdown)) {
        error = GetLastError();
    }
    CloseServiceHandle(service);
    CloseServiceHandle(manager);
    return error;
}

// Runs one of the commands on the installed service.
static DWORD command(const wchar_t *name) {
    SC_HANDLE manager = OpenSCManagerW(NULL, NULL, SC_MANAGER_CONNECT);
    if (manager == NULL) {
        return GetLastError();
    }
    BOOL query = wcscmp(name, L"status") == 0;
    DWORD access = query ? SERVICE_QUERY_STATUS : SERVICE_QUERY_STATUS | SERVICE_START | SERVICE_STOP | DELETE;
    SC_HANDLE service = OpenServiceW(manager, SERVICE_NAME, access);
    if (service == NULL) {
        DWORD error = GetLastError();
        CloseServiceHandle(manager);
        return error;
    }
    DWORD result = NO_ERROR;
    SERVICE_STATUS status;
    if (query) {
        result = QueryServiceStatus(service, &status) ? status.dwCurrentState : GetLastError();
    } else if (wcscmp(name, L"start") == 0) {
        if (!StartServiceW(service, 0, NULL) && GetLastError() != ERROR_SERVICE_ALREADY_RUNNING) {
            result = GetLastError();
        } else if (!await_state(service, SERVICE_RUNNING)) {
            result = ERROR_SERVICE_REQUEST_TIMEOUT;
        }
    } else {
        // Stopped first, also to be removed: njMule saves on its way out.
        if (!ControlService(service, SERVICE_CONTROL_STOP, &status) && GetLastError() != ERROR_SERVICE_NOT_ACTIVE) {
            result = GetLastError();
        } else if (!await_state(service, SERVICE_STOPPED)) {
            result = ERROR_SERVICE_REQUEST_TIMEOUT;
        } else if (wcscmp(name, L"uninstall") == 0 && !DeleteService(service)) {
            result = GetLastError();
        }
    }
    CloseServiceHandle(service);
    CloseServiceHandle(manager);
    return result;
}

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR commandLine, int show) {
    (void) instance;
    (void) previous;
    (void) commandLine;
    (void) show;
    DWORD length = GetModuleFileNameW(NULL, app, ARRAYSIZE(app));
    wchar_t *name = wcsrchr(app, L'\\');
    if (length == 0 || length >= ARRAYSIZE(app) || name == NULL
            || wcscpy_s(name + 1, ARRAYSIZE(app) - (size_t) (name + 1 - app), L"njmule.exe") != 0) {
        return 1;
    }
    PWSTR data = NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_ProgramData, 0, NULL, &data))
            || swprintf(profile, ARRAYSIZE(profile), L"%ls\\njMule", data) < 0) {
        CoTaskMemFree(data);
        return 1;
    }
    CoTaskMemFree(data);
    int argc = 0;
    wchar_t **argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    if (argv != NULL && argc == 2) {
        const wchar_t *verb = argv[1];
        BOOL known = wcscmp(verb, L"install") == 0 || wcscmp(verb, L"uninstall") == 0 || wcscmp(verb, L"start") == 0
                || wcscmp(verb, L"stop") == 0 || wcscmp(verb, L"status") == 0;
        if (known) {
            DWORD result = wcscmp(verb, L"install") == 0 ? install() : command(verb);
            LocalFree(argv);
            return (int) result;
        }
    }
    if (argv != NULL) {
        LocalFree(argv);
    }
    SERVICE_TABLE_ENTRYW services[] = {{(LPWSTR) SERVICE_NAME, service_main}, {NULL, NULL}};
    if (!StartServiceCtrlDispatcherW(services)) {
        // Started by hand rather than by Windows.
        MessageBoxW(
                NULL,
                L"njmule-service.exe is the njMule service, which Windows starts. Install it with:\n"
                L"njmule --service=install",
                L"njMule",
                MB_OK | MB_ICONINFORMATION);
        return 1;
    }
    return 0;
}
