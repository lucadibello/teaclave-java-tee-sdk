#define _GNU_SOURCE
#define _LARGEFILE64_SOURCE
#include <errno.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <netdb.h>
#include <sys/time.h>
#include <time.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <pwd.h>
#include <grp.h>
#include <poll.h>
#include <sys/uio.h>
#include <dirent.h>
#include <stdarg.h>
#include <sys/select.h>
#include <sys/utsname.h>

// Existing stubs for xattr
ssize_t fgetxattr(int fd, const char *name, void *value, size_t size) { errno = ENOTSUP; return -1; }
int     fsetxattr(int fd, const char *name, const void *value, size_t size, int flags) { errno = ENOTSUP; return -1; }
int     fremovexattr(int fd, const char *name) { errno = ENOTSUP; return -1; }
ssize_t flistxattr(int fd, char *list, size_t size) { errno = ENOTSUP; return -1; }

// --- Missing libc symbols Stubs ---

// I/O streams
// Note: We initialize these to NULL. Any access will crash, but in enclave they shouldn't be used directly
// without checking, or the code using them (JDK) checks.
FILE *stderr = NULL;
// FILE *stdin = NULL;
// FILE *stdout = NULL;

int printf(const char *format, ...) { return 0; }
int fprintf(FILE *stream, const char *format, ...) { return 0; }
int puts(const char *s) { return 0; }

// Actual sprintf might be needed? Safe to return 0 for now.
int sprintf(char *str, const char *format, ...) { return 0; }

int vfprintf(FILE *stream, const char *format, va_list ap) { return 0; }
int fflush(FILE *stream) { return 0; }
size_t fwrite(const void *ptr, size_t size, size_t nmemb, FILE *stream) { return 0; }
// size_t fread(void *ptr, size_t size, size_t nmemb, FILE *stream) { return 0; }
// int fputs(const char *s, FILE *stream) { return 0; }
// int fputc(int c, FILE *stream) { return c; }
char *fgets(char *s, int size, FILE *stream) { return NULL; }
FILE *fopen(const char *pathname, const char *mode) { errno = ENOENT; return NULL; }
FILE *fdopen(int fd, const char *mode) { errno = ENOENT; return NULL; }
int fclose(FILE *stream) { return 0; }
// Note: getdelim is defined in tee_sdk_symbol.c

// File System
int open(const char *pathname, int flags, ...) { errno = ENOENT; return -1; }
int open64(const char *pathname, int flags, ...) { errno = ENOENT; return -1; }
int close(int fd) { return 0; }
ssize_t read(int fd, void *buf, size_t count) { errno = EIO; return -1; }
ssize_t write(int fd, const void *buf, size_t count) { return count; }
off_t lseek(int fd, off_t offset, int whence) { errno = ESPIPE; return -1; }
off64_t lseek64(int fd, off64_t offset, int whence) { errno = ESPIPE; return -1; }
int fcntl(int fd, int cmd, ...) { errno = ENOSYS; return -1; }
// int ioctl(int fd, unsigned long request, ...) { errno = ENOSYS; return -1; }
int fsync(int fd) { return 0; }
int fdatasync(int fd) { return 0; }
// int ftruncate(int fd, off_t length) { errno = ENOSYS; return -1; }
int ftruncate64(int fd, off64_t length) { errno = ENOSYS; return -1; }
// int stat(const char *pathname, struct stat *statbuf) { errno = ENOENT; return -1; }
// int fstat(int fd, struct stat *statbuf) { errno = EBADF; return -1; }
// int lstat(const char *pathname, struct stat *statbuf) { errno = ENOENT; return -1; }
int access(const char *pathname, int mode) { errno = ENOENT; return -1; }
int unlink(const char *pathname) { errno = ENOENT; return -1; }
int rename(const char *oldpath, const char *newpath) { errno = ENOENT; return -1; }
int mkdir(const char *pathname, mode_t mode) { errno = ENOSYS; return -1; }
int rmdir(const char *pathname) { errno = ENOENT; return -1; }
char *getcwd(char *buf, size_t size) { errno = ENOSYS; return NULL; }
char *realpath(const char *path, char *resolved_path) { errno = ENOENT; return NULL; }
int utimes(const char *filename, const struct timeval times[2]) { errno = ENOSYS; return -1; }
int fchmod(int fd, mode_t mode) { errno = ENOSYS; return -1; }
int fchown(int fd, uid_t owner, gid_t group) { errno = ENOSYS; return -1; }
int remove(const char *pathname) { errno = ENOENT; return -1; }
ssize_t readlink(const char *pathname, char *buf, size_t bufsiz) { errno = ENOENT; return -1; }
int link(const char *oldpath, const char *newpath) { errno = ENOSYS; return -1; }
long pathconf(const char *path, int name) { errno = EINVAL; return -1; }

// Dir
DIR *opendir(const char *name) { errno = ENOENT; return NULL; }
struct dirent *readdir(DIR *dirp) { return NULL; }
int closedir(DIR *dirp) { return 0; }
// void rewinddir(DIR *dirp) { }

// Network
int socket(int domain, int type, int protocol) { errno = EAFNOSUPPORT; return -1; }
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) { errno = ECONNREFUSED; return -1; }
int bind(int sockfd, const struct sockaddr *addr, socklen_t addrlen) { errno = EADDRNOTAVAIL; return -1; }
int listen(int sockfd, int backlog) { errno = EOPNOTSUPP; return -1; }
int accept(int sockfd, struct sockaddr *addr, socklen_t *addrlen) { errno = EOPNOTSUPP; return -1; }
ssize_t send(int sockfd, const void *buf, size_t len, int flags) { errno = EPIPE; return -1; }
ssize_t recv(int sockfd, void *buf, size_t len, int flags) { errno = EIO; return -1; }
ssize_t sendto(int sockfd, const void *buf, size_t len, int flags, const struct sockaddr *dest_addr, socklen_t addrlen) { errno = EPIPE; return -1; }
ssize_t recvfrom(int sockfd, void *buf, size_t len, int flags, struct sockaddr *src_addr, socklen_t *addrlen) { errno = EIO; return -1; }
int setsockopt(int sockfd, int level, int optname, const void *optval, socklen_t optlen) { errno = ENOPROTOOPT; return -1; }
int getsockopt(int sockfd, int level, int optname, void *optval, socklen_t *optlen) { errno = ENOPROTOOPT; return -1; }
int getsockname(int sockfd, struct sockaddr *addr, socklen_t *addrlen) { errno = EBADF; return -1; }
int shutdown(int sockfd, int how) { errno = ENOTCONN; return -1; }
int socketpair(int domain, int type, int protocol, int sv[2]) { errno = EOPNOTSUPP; return -1; }

// IO Vectors
ssize_t pread64(int fd, void *buf, size_t count, off64_t offset) { errno = ESPIPE; return -1; }
ssize_t pwrite64(int fd, const void *buf, size_t count, off64_t offset) { errno = ESPIPE; return -1; }
ssize_t readv(int fd, const struct iovec *iov, int iovcnt) { errno = EINVAL; return -1; }
ssize_t writev(int fd, const struct iovec *iov, int iovcnt) { errno = EINVAL; return -1; }

// Net Info
int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints, struct addrinfo **res) { return EAI_FAIL; }
void freeaddrinfo(struct addrinfo *res) { }
int getnameinfo(const struct sockaddr *sa, socklen_t salen, char *host, socklen_t hostlen, char *serv, socklen_t servlen, int flags) { return EAI_FAIL; }
const char *gai_strerror(int errcode) { return "Unknown error"; }
int gethostname(char *name, size_t len) { errno = ENOSYS; return -1; }

// Process/Time/Misc
pid_t getpid(void) { return 1; }
uid_t getuid(void) { return 0; }
int gettimeofday(struct timeval *tv, void *tz) { return -1; }
int clock_gettime(clockid_t clk_id, struct timespec *tp) { return -1; }
time_t time(time_t *tloc) { return (time_t)-1; }
struct tm *localtime_r(const time_t *timep, struct tm *result) { return NULL; }
struct tm *gmtime_r(const time_t *timep, struct tm *result) { return NULL; }
long sysconf(int name) { return -1; }
void exit(int status) { while(1); }
// void abort(void) { while(1); }
// int kill(pid_t pid, int sig) { errno = EPERM; return -1; }
// pid_t wait(int *wstatus) { errno = ECHILD; return -1; }
int dup(int oldfd) { errno = EBADF; return -1; }
int dup2(int oldfd, int newfd) { errno = EBADF; return -1; }
// int pipe(int pipefd[2]) { errno = EMFILE; return -1; }
int poll(struct pollfd *fds, nfds_t nfds, int timeout) { errno = ENOSYS; return -1; }
// int select(int nfds, fd_set *readfds, fd_set *writefds, fd_set *exceptfds, struct timeval *timeout) { errno = ENOSYS; return -1; }

// void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset) { errno = ENOMEM; return MAP_FAILED; }
void *mmap64(void *addr, size_t length, int prot, int flags, int fd, off64_t offset) { errno = ENOMEM; return MAP_FAILED; }
// int munmap(void *addr, size_t length) { return 0; }
int nanosleep(const struct timespec *req, struct timespec *rem) { return 0; }
int sched_yield(void) { return 0; }

// User Info
struct passwd *getpwuid(uid_t uid) { return NULL; }
int getpwuid_r(uid_t uid, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) { return -1; }
struct group *getgrgid(gid_t gid) { return NULL; }
int getgrgid_r(gid_t gid, struct group *grp, char *buf, size_t buflen, struct group **result) { return -1; }
// int getpwnam_r(const char *name, struct passwd *pwd, char *buf, size_t buflen, struct passwd **result) { return -1; }
// int getgrnam_r(const char *name, struct group *grp, char *buf, size_t buflen, struct group **result) { return -1; }

// DL
// void *dlopen(const char *filename, int flags) { return NULL; }
// void *dlsym(void *handle, const char *symbol) { return NULL; }
// int dlclose(void *handle) { return 0; }
// char *dlerror(void) { return "Not supported"; }
int dladdr(const void *addr, Dl_info *info) { return 0; }

// Pthread
int pthread_condattr_destroy(void *attr) { return 0; }

// Missing Symbols for SGX SDK / GraalVM
void *_mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset) {
    return mmap(addr, length, prot, flags, fd, offset);
}
int _munmap(void *addr, size_t length) {
    return munmap(addr, length);
}

int uname(struct utsname *buf) {
    if (buf) {
        strcpy(buf->sysname, "Linux");
        strcpy(buf->nodename, "sgx-enclave");
        strcpy(buf->release, "5.4.0");
        strcpy(buf->version, "#1");
        strcpy(buf->machine, "x86_64");
    }
    return 0;
}

void rewind(FILE *stream) { }
int ferror(FILE *stream) { return 0; }
int feof(FILE *stream) { return 0; }
