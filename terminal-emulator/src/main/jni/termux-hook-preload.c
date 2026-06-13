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
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

// The app data directory prefix we check against
#define APP_DATA_PREFIX "/data/data/com.sm64builder"

// Old Termux path prefix that may appear in bootstrap scripts/configs
#define OLD_TERMUX_PREFIX "/data/data/com.termux"

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

// Helper: check if file is an ELF binary by reading its magic bytes
static int is_elf_binary(const char* path) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    char magic[4];
    int n = read(fd, magic, 4);
    close(fd);
    return (n == 4) && magic[0] == 0x7f && magic[1] == 'E' &&
           magic[2] == 'L' && magic[3] == 'F';
}

// Helper: parse shebang from a script file and extract interpreter path.
// Returns 0 on success, -1 if not a shebang script.
// Automatically remaps /data/data/com.termux paths to the correct package path.
static int parse_shebang(const char* path, char* interp, size_t interp_size) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 2 || buf[0] != '#' || buf[1] != '!') return -1;

    // Skip past "#!" and any whitespace
    int pos = 2;
    while (pos < n && (buf[pos] == ' ' || buf[pos] == '\t')) pos++;

    // Copy interpreter path until whitespace or newline
    int i = 0;
    while (pos < n && buf[pos] != '\n' && buf[pos] != '\r') {
        if (buf[pos] == ' ' || buf[pos] == '\t') break;
        if (i < (int)interp_size - 1) interp[i++] = buf[pos];
        pos++;
    }
    interp[i] = '\0';
    if (i <= 0) return -1;

    // Remap old Termux prefix to current package path
    size_t old_len = strlen(OLD_TERMUX_PREFIX);
    size_t new_len = strlen(APP_DATA_PREFIX);
    if (strncmp(interp, OLD_TERMUX_PREFIX, old_len) == 0) {
        // Shift the path tail and insert new prefix
        char *tail = interp + old_len;
        size_t tail_len = strlen(tail) + 1;
        if (old_len != new_len) {
            // Need to shift: move tail to make room for new prefix length
            memmove(interp + new_len, tail, tail_len);
        }
        memcpy(interp, APP_DATA_PREFIX, new_len);
    }
    return 0;
}

// Build new argv for executing an ELF binary through linker64:
// [linker64, original_path, original_argv[1..n], NULL]
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

// Build new argv for executing a script through linker64 via its interpreter:
// [linker64, interpreter_path, script_path, original_argv[1..n], NULL]
static char** build_linker_argv_for_interp(const char* interp, const char* script,
                                           char* const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;

    char** new_argv = malloc((argc + 4) * sizeof(char*));
    if (!new_argv) return NULL;

    new_argv[0] = (char*)SYSTEM_LINKER;
    new_argv[1] = (char*)interp;
    new_argv[2] = (char*)script;
    for (int i = 1; i <= argc; i++) {
        new_argv[i + 2] = argv[i];
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
        if (is_elf_binary(pathname)) {
            // ELF binary -> redirect through system linker
            char** new_argv = build_linker_argv(pathname, argv);
            if (new_argv) {
                int ret = real_execve(SYSTEM_LINKER, new_argv, envp);
                free(new_argv);
                return ret;
            }
        } else {
            // Check if it's a script with shebang
            char interp[384];
            if (parse_shebang(pathname, interp, sizeof(interp)) == 0) {
                // Script -> run interpreter via linker64 with script as arg
                // This avoids the kernel's shebang handling which would fail
                // when trying to exec the interpreter from app data
                char** new_argv = build_linker_argv_for_interp(interp, pathname, argv);
                if (new_argv) {
                    int ret = real_execve(SYSTEM_LINKER, new_argv, envp);
                    free(new_argv);
                    return ret;
                }
            }
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
