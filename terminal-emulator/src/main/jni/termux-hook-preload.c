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
#include <dirent.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <unistd.h>

// Fallback if NDK doesn't define SYS_readlinkat (ARM64 Linux syscall #78)
#ifndef SYS_readlinkat
#define SYS_readlinkat 78
#endif

// The app data directory prefix we check against
#define APP_DATA_PREFIX "/data/data/com.sm64builder"

// Old Termux path prefix that may appear in bootstrap scripts/configs
#define OLD_TERMUX_PREFIX "/data/data/com.termux"
// Same prefix without leading slash (for dpkg-format ./data/data/com.termux paths)
#define OLD_TERMUX_PREFIX_REL "data/data/com.termux"

// Type signatures for exec variants
typedef int (*execve_func_t)(const char*, char* const[], char* const[]);
typedef int (*execvp_func_t)(const char*, char* const[]);

// The system linker executable (for exec'ing scripts through linker64)
#define SYSTEM_LINKER "/system/bin/linker64"

// Helper: check if path is in the app data directory.
// Handles absolute paths and relative paths (resolves via realpath).
static int is_app_data_path(const char* path) {
    if (!path) return 0;
    if (path[0] == '/') {
        size_t prefix_len = strlen(APP_DATA_PREFIX);
        return strncmp(path, APP_DATA_PREFIX, prefix_len) == 0;
    }
    // Relative path — resolve to absolute using realpath then check
    char resolved[4096];
    if (realpath(path, resolved)) {
        size_t prefix_len = strlen(APP_DATA_PREFIX);
        return strncmp(resolved, APP_DATA_PREFIX, prefix_len) == 0;
    }
    return 0;
}

// Helper: check if file is an ELF binary by reading its magic bytes.
// Handles both absolute and relative paths (resolves via realpath).
static int is_elf_binary(const char* path) {
    if (!path) return 0;
    char resolved[4096];
    const char* p = path;
    if (path[0] != '/') {
        if (!realpath(path, resolved)) return 0;
        p = resolved;
    }
    int fd = open(p, O_RDONLY);
    if (fd < 0) return 0;
    char magic[4];
    int n = read(fd, magic, 4);
    close(fd);
    return (n == 4) && magic[0] == 0x7f && magic[1] == 'E' &&
           magic[2] == 'L' && magic[3] == 'F';
}

// Helper: parse shebang from a script file and extract interpreter path.
// Returns 0 on success, -1 if not a shebang script.
// Remaps old Termux paths in the interpreter path.
static int parse_shebang(const char* path, char* interp, size_t interp_size) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    char buf[384];
    int n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 2 || buf[0] != '#' || buf[1] != '!') return -1;

    int pos = 2;
    while (pos < n && (buf[pos] == ' ' || buf[pos] == '\t')) pos++;

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
        char *tail = interp + old_len;
        size_t tail_len = strlen(tail) + 1;
        if (new_len + tail_len > interp_size) return -1;
        if (old_len != new_len) {
            memmove(interp + new_len, tail, tail_len);
        }
        memcpy(interp, APP_DATA_PREFIX, new_len);
    }
    return 0;
}

// Build new argv for executing an ELF binary through linker64:
// [linker64, pathname, original_argv[1..n], NULL]
static char** build_linker_argv(const char* pathname, char* const argv[]) {
    int argc = 0;
    while (argv && argv[argc]) argc++;

    char** new_argv = malloc((argc + 3) * sizeof(char*));
    if (!new_argv) return NULL;

    new_argv[0] = (char*)SYSTEM_LINKER;
    new_argv[1] = (char*)pathname;
    // Skip argv[0] — linker provides pathname as argv[0]
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
    // Skip argv[0] — linker provides script path as argv[0]
    for (int i = 1; i <= argc; i++) {
        new_argv[i + 2] = argv[i];
    }
    return new_argv;
}

// Helper: check if path is in the old Termux data directory.
// Handles both /data/data/com.termux and ./data/data/com.termux (dpkg format)
// and data/data/com.termux (relative paths from tar extraction).
static int is_old_termux_path(const char* path) {
    if (!path) return 0;
    const char* p = path;
    // Skip leading "./" used by dpkg and tar
    if (p[0] == '.' && p[1] == '/') p += 2;
    return strncmp(p, OLD_TERMUX_PREFIX, strlen(OLD_TERMUX_PREFIX)) == 0 ||
           strncmp(p, OLD_TERMUX_PREFIX_REL, strlen(OLD_TERMUX_PREFIX_REL)) == 0;
}

// Remap /data/data/com.termux -> /data/data/com.sm64builder in file paths.
// Handles both /data/data/com.termux and ./data/data/com.termux (dpkg format).
// Also handles doubled paths like /data/data/com.sm64builder/.../data/data/com.termux/
// which can arise when apt combines Dir with a relative path that still contains
// the old prefix (e.g. eipp.log.xz paths).
// Returns the original path if no remapping needed, otherwise writes to buf.
static const char* remap_path(const char* path, char* buf, size_t size) {
    if (!path) return path;
    const char* p = path;
    int dot_slash = 0;
    if (p[0] == '.' && p[1] == '/') {
        p += 2;
        dot_slash = 1;
    }

    // First check for doubled path: APP_PREFIX + /data/data/com.termux embedded
    // e.g. /data/data/com.sm64builder/files/usr/data/data/com.termux/files/usr/...
    // The OLD_TERMUX_PREFIX appears INSIDE the APP_DATA_PREFIX path.
    size_t app_prefix_len = strlen(APP_DATA_PREFIX);
    size_t old_prefix_len = strlen(OLD_TERMUX_PREFIX);
    size_t old_rel_len = strlen(OLD_TERMUX_PREFIX_REL);
    if (strncmp(p, APP_DATA_PREFIX, app_prefix_len) == 0) {
        // Look for OLD_TERMUX_PREFIX after APP_DATA_PREFIX in the path
        const char* old_pos = strstr(p + app_prefix_len, OLD_TERMUX_PREFIX);
        // Also check for relative form (without leading /)
        if (!old_pos)
            old_pos = strstr(p + app_prefix_len, OLD_TERMUX_PREFIX_REL);
        if (old_pos) {
            // Found OLD_TERMUX_PREFIX embedded at a deeper path level.
            // Strip everything from the embedded OLD_TERMUX_PREFIX back,
            // keeping only APP_PREFIX + everything after OLD_TERMUX_PREFIX.
            const char* after;
            // Use relative old prefix to compute after (no leading slash added)
            if (strncmp(old_pos, OLD_TERMUX_PREFIX_REL, old_rel_len) == 0)
                after = old_pos + old_rel_len;
            else
                after = old_pos + old_prefix_len;
            if (dot_slash) {
                snprintf(buf, size, "./%s%s", APP_DATA_PREFIX, after);
            } else {
                snprintf(buf, size, "%s%s", APP_DATA_PREFIX, after);
            }
            return buf;
        }
    }

    // Standard old prefix remapping
    size_t old_len = strlen(OLD_TERMUX_PREFIX);
    if (strncmp(p, OLD_TERMUX_PREFIX, old_len) == 0) {
        if (dot_slash) {
            snprintf(buf, size, "./%s%s", APP_DATA_PREFIX, p + old_len);
        } else {
            snprintf(buf, size, "%s%s", APP_DATA_PREFIX, p + old_len);
        }
        return buf;
    }
    // Also check for relative form (without leading /)
    if (strncmp(p, OLD_TERMUX_PREFIX_REL, old_rel_len) == 0) {
        if (dot_slash) {
            snprintf(buf, size, "./%s/%s", APP_DATA_PREFIX, p + old_rel_len);
        } else {
            snprintf(buf, size, "%s/%s", APP_DATA_PREFIX, p + old_rel_len);
        }
        return buf;
    }
    return path;
}

// ============================================================
// File operation interceptions: remap old Termux paths
// at the libc level. This catches all hardcoded /data/data/
// com.termux/ paths that binaries may have compiled in.
// ============================================================

// Intercept open() — remap old Termux paths in file paths.
int open(const char* path, int flags, ...) {
    static int (*real_open)(const char*, int, ...) = NULL;
    if (!real_open) {
        real_open = dlsym(RTLD_NEXT, "open");
        if (!real_open) _exit(127);
    }

    char rbuf[4096];
    const char* p = remap_path(path, rbuf, sizeof(rbuf));
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        int mode = va_arg(ap, int); va_end(ap);
        return real_open(p, flags, mode);
    }
    return real_open(p, flags);
}

// Intercept openat() — remap paths for absolute/relative paths.
int openat(int dirfd, const char* path, int flags, ...) {
    static int (*real_openat)(int, const char*, int, ...) = NULL;
    if (!real_openat) {
        real_openat = dlsym(RTLD_NEXT, "openat");
        if (!real_openat) _exit(127);
    }

    const char* p = path;
    char rbuf[4096];
    if (path && (path[0] == '/' || (path[0] == '.' && path[1] == '/')))
        p = remap_path(path, rbuf, sizeof(rbuf));
    if (flags & O_CREAT) {
        va_list ap; va_start(ap, flags);
        int mode = va_arg(ap, int); va_end(ap);
        return real_openat(dirfd, p, flags, mode);
    }
    return real_openat(dirfd, p, flags);
}

// Intercept stat() — remap paths
int stat(const char* path, struct stat* st) {
    static int (*real_stat)(const char*, struct stat*) = NULL;
    if (!real_stat) {
        real_stat = dlsym(RTLD_NEXT, "stat");
        if (!real_stat) _exit(127);
    }
    char buf[4096];
    return real_stat(remap_path(path, buf, sizeof(buf)), st);
}

// Intercept lstat() — same
int lstat(const char* path, struct stat* st) {
    static int (*real_lstat)(const char*, struct stat*) = NULL;
    if (!real_lstat) {
        real_lstat = dlsym(RTLD_NEXT, "lstat");
        if (!real_lstat) _exit(127);
    }
    char buf[4096];
    return real_lstat(remap_path(path, buf, sizeof(buf)), st);
}

// Intercept access() — remap paths
int access(const char* path, int mode) {
    static int (*real_access)(const char*, int) = NULL;
    if (!real_access) {
        real_access = dlsym(RTLD_NEXT, "access");
        if (!real_access) _exit(127);
    }
    char buf[4096];
    return real_access(remap_path(path, buf, sizeof(buf)), mode);
}

// Intercept opendir() — apt uses this to check directory existence
DIR *opendir(const char *name) {
    static DIR *(*real_opendir)(const char*) = NULL;
    if (!real_opendir) {
        real_opendir = dlsym(RTLD_NEXT, "opendir");
        if (!real_opendir) _exit(127);
    }
    char buf[4096];
    return real_opendir(remap_path(name, buf, sizeof(buf)));
}

// Intercept rename() — dpkg uses this to move .dpkg-new files to final names
int rename(const char* oldpath, const char* newpath) {
    static int (*real_rename)(const char*, const char*) = NULL;
    if (!real_rename) {
        real_rename = dlsym(RTLD_NEXT, "rename");
        if (!real_rename) _exit(127);
    }
    char oldbuf[4096], newbuf[4096];
    return real_rename(remap_path(oldpath, oldbuf, sizeof(oldbuf)),
                       remap_path(newpath, newbuf, sizeof(newbuf)));
}

// Intercept utimensat() — dpkg uses this to set file timestamps
int utimensat(int dirfd, const char* pathname, const struct timespec times[2], int flags) {
    static int (*real_utimensat)(int, const char*, const struct timespec*, int) = NULL;
    if (!real_utimensat) {
        real_utimensat = dlsym(RTLD_NEXT, "utimensat");
        if (!real_utimensat) _exit(127);
    }
    char buf[4096];
    const char* p = remap_path(pathname, buf, sizeof(buf));
    return real_utimensat(dirfd, p, times, flags);
}

// Intercept mkdir() — dpkg uses this to create directories during unpack
int mkdir(const char* pathname, mode_t mode) {
    static int (*real_mkdir)(const char*, mode_t) = NULL;
    if (!real_mkdir) {
        real_mkdir = dlsym(RTLD_NEXT, "mkdir");
        if (!real_mkdir) _exit(127);
    }
    char buf[4096];
    return real_mkdir(remap_path(pathname, buf, sizeof(buf)), mode);
}

// ============================================================
// Batch file operation interceptions: cover all functions dpkg
// may use during package installation. Each remaps old Termux
// paths to the current package data directory.
// ============================================================

// Intercept chmod() — dpkg uses this to set file permissions on .dpkg-new files
int chmod(const char* pathname, mode_t mode) {
    static int (*real_chmod)(const char*, mode_t) = NULL;
    if (!real_chmod) {
        real_chmod = dlsym(RTLD_NEXT, "chmod");
        if (!real_chmod) _exit(127);
    }
    char buf[4096];
    return real_chmod(remap_path(pathname, buf, sizeof(buf)), mode);
}

// Intercept chown() — dpkg may use this to set file ownership
int chown(const char* pathname, uid_t owner, gid_t group) {
    static int (*real_chown)(const char*, uid_t, gid_t) = NULL;
    if (!real_chown) {
        real_chown = dlsym(RTLD_NEXT, "chown");
        if (!real_chown) _exit(127);
    }
    char buf[4096];
    return real_chown(remap_path(pathname, buf, sizeof(buf)), owner, group);
}

// Intercept symlink() — dpkg creates symlinks for conffiles
int symlink(const char* target, const char* linkpath) {
    static int (*real_symlink)(const char*, const char*) = NULL;
    if (!real_symlink) {
        real_symlink = dlsym(RTLD_NEXT, "symlink");
        if (!real_symlink) _exit(127);
    }
    char buf[4096];
    return real_symlink(target, remap_path(linkpath, buf, sizeof(buf)));
}

// Intercept unlink() — dpkg removes temp files
int unlink(const char* pathname) {
    static int (*real_unlink)(const char*) = NULL;
    if (!real_unlink) {
        real_unlink = dlsym(RTLD_NEXT, "unlink");
        if (!real_unlink) _exit(127);
    }
    char buf[4096];
    return real_unlink(remap_path(pathname, buf, sizeof(buf)));
}

// Intercept readlink() — dpkg reads symlinks for conffile handling.
// Also remaps old Termux paths.
ssize_t readlink(const char* pathname, char* buf, size_t size) {
    static ssize_t (*real_readlink)(const char*, char*, size_t) = NULL;
    if (!real_readlink) {
        real_readlink = dlsym(RTLD_NEXT, "readlink");
        if (!real_readlink) _exit(127);
    }
    char pbuf[4096];
    return real_readlink(remap_path(pathname, pbuf, sizeof(pbuf)), buf, size);
}

// Intercept readlinkat() — alternative to readlink()
ssize_t readlinkat(int dirfd, const char* pathname, char* buf, size_t size) {
    static ssize_t (*real_readlinkat)(int, const char*, char*, size_t) = NULL;
    if (!real_readlinkat) {
        real_readlinkat = dlsym(RTLD_NEXT, "readlinkat");
        if (!real_readlinkat) _exit(127);
    }
    char pbuf[4096];
    const char* p = pathname;
    if (pathname && (pathname[0] == '/' || (pathname[0] == '.' && pathname[1] == '/')))
        p = remap_path(pathname, pbuf, sizeof(pbuf));
    return real_readlinkat(dirfd, p, buf, size);
}

// Intercept rmdir() — dpkg removes temp directories
int rmdir(const char* pathname) {
    static int (*real_rmdir)(const char*) = NULL;
    if (!real_rmdir) {
        real_rmdir = dlsym(RTLD_NEXT, "rmdir");
        if (!real_rmdir) _exit(127);
    }
    char buf[4096];
    return real_rmdir(remap_path(pathname, buf, sizeof(buf)));
}

// Intercept mkdirat() — dpkg may use this instead of mkdir()
int mkdirat(int dirfd, const char* pathname, mode_t mode) {
    static int (*real_mkdirat)(int, const char*, mode_t) = NULL;
    if (!real_mkdirat) {
        real_mkdirat = dlsym(RTLD_NEXT, "mkdirat");
        if (!real_mkdirat) _exit(127);
    }
    char buf[4096];
    const char* p = pathname;
    if (pathname && (pathname[0] == '/' || (pathname[0] == '.' && pathname[1] == '/')))
        p = remap_path(pathname, buf, sizeof(buf));
    return real_mkdirat(dirfd, p, mode);
}

// Intercept fchmodat() — dpkg may use this instead of chmod()
int fchmodat(int dirfd, const char* pathname, mode_t mode, int flags) {
    static int (*real_fchmodat)(int, const char*, mode_t, int) = NULL;
    if (!real_fchmodat) {
        real_fchmodat = dlsym(RTLD_NEXT, "fchmodat");
        if (!real_fchmodat) _exit(127);
    }
    char buf[4096];
    const char* p = pathname;
    if (pathname && (pathname[0] == '/' || (pathname[0] == '.' && pathname[1] == '/')))
        p = remap_path(pathname, buf, sizeof(buf));
    return real_fchmodat(dirfd, p, mode, flags);
}

// Intercept renameat() — dpkg may use this instead of rename()
int renameat(int olddirfd, const char* oldpath, int newdirfd, const char* newpath) {
    static int (*real_renameat)(int, const char*, int, const char*) = NULL;
    if (!real_renameat) {
        real_renameat = dlsym(RTLD_NEXT, "renameat");
        if (!real_renameat) _exit(127);
    }
    char oldbuf[4096], newbuf[4096];
    const char* op = oldpath;
    const char* np = newpath;
    if (oldpath && (oldpath[0] == '/' || (oldpath[0] == '.' && oldpath[1] == '/')))
        op = remap_path(oldpath, oldbuf, sizeof(oldbuf));
    if (newpath && (newpath[0] == '/' || (newpath[0] == '.' && newpath[1] == '/')))
        np = remap_path(newpath, newbuf, sizeof(newbuf));
    return real_renameat(olddirfd, op, newdirfd, np);
}

// Intercept unlinkat() — dpkg may use this instead of unlink()
int unlinkat(int dirfd, const char* pathname, int flags) {
    static int (*real_unlinkat)(int, const char*, int) = NULL;
    if (!real_unlinkat) {
        real_unlinkat = dlsym(RTLD_NEXT, "unlinkat");
        if (!real_unlinkat) _exit(127);
    }
    char buf[4096];
    const char* p = pathname;
    if (pathname && (pathname[0] == '/' || (pathname[0] == '.' && pathname[1] == '/')))
        p = remap_path(pathname, buf, sizeof(buf));
    return real_unlinkat(dirfd, p, flags);
}

// ============================================================
// Exec function interceptions
// ELF binaries: exec'd directly by kernel (works on Android 16).
// Scripts in app data: redirect through linker64 since the kernel's
// shebang handling can't exec the interpreter (also in app data).
// ============================================================
int execve(const char* pathname, char* const argv[], char* const envp[]) {
    // Remap old Termux paths to current package path
    char remapped[4096];
    if (is_old_termux_path(pathname)) {
        const char* p = pathname;
        int dot_slash = 0;
        if (p[0] == '.' && p[1] == '/') { p += 2; dot_slash = 1; }
        if (strncmp(p, OLD_TERMUX_PREFIX_REL, strlen(OLD_TERMUX_PREFIX_REL)) == 0) {
            if (dot_slash)
                snprintf(remapped, sizeof(remapped), "./%s/%s", APP_DATA_PREFIX, p + strlen(OLD_TERMUX_PREFIX_REL));
            else
                snprintf(remapped, sizeof(remapped), "%s/%s", APP_DATA_PREFIX, p + strlen(OLD_TERMUX_PREFIX_REL));
        } else {
            if (dot_slash)
                snprintf(remapped, sizeof(remapped), "./%s%s", APP_DATA_PREFIX, p + strlen(OLD_TERMUX_PREFIX));
            else
                snprintf(remapped, sizeof(remapped), "%s%s", APP_DATA_PREFIX, p + strlen(OLD_TERMUX_PREFIX));
        }
        pathname = remapped;
    }

    static execve_func_t real_execve = NULL;
    if (!real_execve) {
        real_execve = (execve_func_t)dlsym(RTLD_NEXT, "execve");
        if (!real_execve) _exit(127);
    }

    // Scripts in app data: kernel can't exec the shebang interpreter
    // (it's also in app data, blocked by Android 16). Redirect through
    // linker64 which loads the interpreter via open()+mmap.
    if (is_app_data_path(pathname) && !is_elf_binary(pathname)) {
        char interp[384];
        if (parse_shebang(pathname, interp, sizeof(interp)) == 0) {
            char** new_argv = build_linker_argv_for_interp(interp, pathname, argv);
            if (new_argv) {
                int ret = real_execve(SYSTEM_LINKER, new_argv, envp);
                free(new_argv);
                return ret;
            }
        }
    }

    // For ELF binaries in app data: try direct exec first (works when
    // parent process was loaded through DT_INTERP), fall back to linker64
    // if kernel blocks direct exec (e.g. when parent was loaded via linker64
    // from termux.c's initial shell).
    if (is_app_data_path(pathname) && is_elf_binary(pathname)) {
        int ret = real_execve(pathname, argv, envp);
        if (ret != -1) return ret;
        // EACCES: fall back to linker64
        char** new_argv = build_linker_argv(pathname, argv);
        if (new_argv) {
            ret = real_execve(SYSTEM_LINKER, new_argv, envp);
            free(new_argv);
            return ret;
        }
    }

    return real_execve(pathname, argv, envp);
}

// Intercepted execvp — only remaps old paths, no linker64 redirect.
int execvp(const char* file, char* const argv[]) {
    // Remap old Termux paths to current package path
    char remapped[4096];
    if (file && is_old_termux_path(file)) {
        const char* p = file;
        if (p[0] == '.' && p[1] == '/') p += 2;
        if (strncmp(p, OLD_TERMUX_PREFIX_REL, strlen(OLD_TERMUX_PREFIX_REL)) == 0) {
            snprintf(remapped, sizeof(remapped), "%s/%s", APP_DATA_PREFIX,
                     p + strlen(OLD_TERMUX_PREFIX_REL));
        } else {
            snprintf(remapped, sizeof(remapped), "%s%s", APP_DATA_PREFIX,
                     p + strlen(OLD_TERMUX_PREFIX));
        }
        file = remapped;
    }

    static execvp_func_t real_execvp = NULL;
    if (!real_execvp) {
        real_execvp = (execvp_func_t)dlsym(RTLD_NEXT, "execvp");
        if (!real_execvp) _exit(127);
    }
    return real_execvp(file, argv);
}
