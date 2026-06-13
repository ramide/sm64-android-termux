// ============================================================
// LD_PRELOAD hook: intercept execve() and redirect through
// /system/bin/linker64 to bypass Android 16's exec restriction.
//
// On Android 16+, the kernel blocks execve() for binaries
// inside app data directories (/data/data/<pkg>/). The
// workaround invokes /system/bin/linker64 instead, which is
// a system binary and can be exec'd freely. The linker then
// opens the target binary via open()+mmap (not execve),
// bypassing the restriction.
//
// This is set via LD_PRELOAD in the shell environment so that
// ALL exec calls made from within the shell (by bash or any
// subprocess) transparently go through the linker without any
// script wrappers or bash functions needed.
// ============================================================
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// The app data directory prefix we check against
#define APP_DATA_PREFIX "/data/data/com.sm64builder"

// The system linker executable
#define SYSTEM_LINKER "/system/bin/linker64"

// Type signatures for exec variants
typedef int (*execve_func_t)(const char*, char* const[], char* const[]);
typedef int (*execvp_func_t)(const char*, char* const[]);

// Forward declaration for environ
extern char **environ;

// Helper: check if path is in the app data directory
static int is_app_data_path(const char* path) {
    size_t prefix_len = strlen(APP_DATA_PREFIX);
    return path && strncmp(path, APP_DATA_PREFIX, prefix_len) == 0;
}

// Build new argv with linker64 as the executable and the original
// path as argv[1]. The caller must free the returned pointer.
static char** build_linker_argv(const char* pathname, char* const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;

    char** new_argv = malloc((argc + 3) * sizeof(char*));
    if (!new_argv) return NULL;

    new_argv[0] = (char*)SYSTEM_LINKER;
    new_argv[1] = (char*)pathname;
    for (int i = 1; i <= argc; i++) {
        new_argv[i + 1] = argv[i];
    }
    return new_argv;
}

// Intercepted execve
int execve(const char* pathname, char* const argv[], char* const envp[]) {
    static execve_func_t real_execve = NULL;
    if (!real_execve) {
        real_execve = (execve_func_t)dlsym(RTLD_NEXT, "execve");
        if (!real_execve) _exit(127);
    }

    if (is_app_data_path(pathname)) {
        char** new_argv = build_linker_argv(pathname, argv);
        if (new_argv) {
            int ret = real_execve(SYSTEM_LINKER, new_argv, envp);
            free(new_argv);
            return ret;
        }
    }

    return real_execve(pathname, argv, envp);
}

// Intercepted execvp — handles PATH-relative lookups
int execvp(const char* file, char* const argv[]) {
    static execvp_func_t real_execvp = NULL;
    if (!real_execvp) {
        real_execvp = (execvp_func_t)dlsym(RTLD_NEXT, "execvp");
        if (!real_execvp) _exit(127);
    }

    // If the path contains a slash, it's an absolute/relative path
    if (file && strchr(file, '/')) {
        if (is_app_data_path(file)) {
            char** new_argv = build_linker_argv(file, argv);
            if (new_argv) {
                int ret = execve(file, new_argv, environ);
                free(new_argv);
                return ret;
            }
        }
        return real_execvp(file, argv);
    }

    // No slash: let the real execvp handle PATH search
    return real_execvp(file, argv);
}
