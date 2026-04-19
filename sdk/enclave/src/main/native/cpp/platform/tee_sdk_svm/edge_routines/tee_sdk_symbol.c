// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "tee_sdk_enclave_t.h"
#include "tee_sdk_symbol.h"

int enable_trace_symbol_calling = 0x0;

void __fxstat() {TRACE_SYMBOL_CALL(); ASSERT();}
void __fxstat64() {TRACE_SYMBOL_CALL(); ASSERT();}
void __libc_current_sigrtmax() {TRACE_SYMBOL_CALL(); ASSERT();}
void __libc_malloc() {TRACE_SYMBOL_CALL(); ASSERT();}
void __lxstat() {TRACE_SYMBOL_CALL(); ASSERT();}
void __lxstat64() {TRACE_SYMBOL_CALL(); ASSERT();}
void __sched_cpucount() {TRACE_SYMBOL_CALL(); ASSERT();}
void __strdup() {TRACE_SYMBOL_CALL(); ASSERT();}
void __xmknod() {TRACE_SYMBOL_CALL(); ASSERT();}
void __xpg_strerror_r() {TRACE_SYMBOL_CALL(); ASSERT();}
void __xstat() {TRACE_SYMBOL_CALL(); ASSERT();}
void __xstat64() {TRACE_SYMBOL_CALL(); ASSERT();}
void chmod() {TRACE_SYMBOL_CALL(); ASSERT();}
void chown() {TRACE_SYMBOL_CALL(); ASSERT();}
void crc32() {TRACE_SYMBOL_CALL(); ASSERT();}
void deflate() {TRACE_SYMBOL_CALL(); ASSERT();}
void deflateBound() {TRACE_SYMBOL_CALL(); ASSERT();}
void deflateEnd() {TRACE_SYMBOL_CALL(); ASSERT();}
void deflateInit2_() {TRACE_SYMBOL_CALL(); ASSERT();}
void deflateSetHeader() {TRACE_SYMBOL_CALL(); ASSERT();}
void dlopen() {TRACE_SYMBOL_CALL(); ASSERT();}
void dlsym() {TRACE_SYMBOL_CALL(); ASSERT();}
void endmntent() {TRACE_SYMBOL_CALL(); ASSERT();}
void fputs() {TRACE_SYMBOL_CALL(); ASSERT();}
void fscanf() {TRACE_SYMBOL_CALL(); ASSERT();}
void fstatvfs() {TRACE_SYMBOL_CALL(); ASSERT();}
void fstatvfs64() {TRACE_SYMBOL_CALL(); ASSERT();}
void getgrnam_r() {TRACE_SYMBOL_CALL(); ASSERT();}
void getmntent_r() {TRACE_SYMBOL_CALL(); ASSERT();}
void getpwnam_r() {TRACE_SYMBOL_CALL(); ASSERT();}
void inet_pton() {TRACE_SYMBOL_CALL(); ASSERT();}
void inflate() {TRACE_SYMBOL_CALL(); ASSERT();}
void inflateEnd() {TRACE_SYMBOL_CALL(); ASSERT();}
void inflateInit2_() {TRACE_SYMBOL_CALL(); ASSERT();}
void inflateReset() {TRACE_SYMBOL_CALL(); ASSERT();}
void inflateSetDictionary() {TRACE_SYMBOL_CALL(); ASSERT();}
void ioctl() {TRACE_SYMBOL_CALL(); ASSERT();}
void lchown() {TRACE_SYMBOL_CALL(); ASSERT();}
void mknod() {TRACE_SYMBOL_CALL(); ASSERT();}
void pipe() {TRACE_SYMBOL_CALL(); ASSERT();}
void pthread_kill() {TRACE_SYMBOL_CALL(); ASSERT();}
void sched_getaffinity() {TRACE_SYMBOL_CALL(); ASSERT();}
void sendfile() {TRACE_SYMBOL_CALL(); ASSERT();}
void sendfile64() {TRACE_SYMBOL_CALL(); ASSERT();}
void setmntent() {TRACE_SYMBOL_CALL(); ASSERT();}
void sigaction() {TRACE_SYMBOL_CALL(); ASSERT();}
void sigaddset() {TRACE_SYMBOL_CALL(); ASSERT();}
void sigemptyset() {TRACE_SYMBOL_CALL(); ASSERT();}
void sigprocmask() {TRACE_SYMBOL_CALL(); ASSERT();}
void statvfs() {TRACE_SYMBOL_CALL(); ASSERT();}
void statvfs64() {TRACE_SYMBOL_CALL(); ASSERT();}
void symlink() {TRACE_SYMBOL_CALL(); ASSERT();}
void timezone() {TRACE_SYMBOL_CALL(); ASSERT();}

char* strcat(char* dest, const char* source) {
    TRACE_SYMBOL_CALL();
	if (dest == NULL || source == NULL) { return dest; }
	char* p = dest;
	while (*p != '\0') { p++; }
	while (*source != '\0') { *p = *source; p++; source++; }
	*p = '\0';
	return dest;
}

char* strcpy(char* dest, const char* sourse) {
    TRACE_SYMBOL_CALL();
    if(dest==NULL || sourse==NULL) return NULL;
    char* res=dest;
    while((*dest++ = *sourse++)!='\0');
    return res;
}

char* stpcpy(char *dest, const char *sourse) {
    TRACE_SYMBOL_CALL();
    strcpy(dest, sourse);
    return dest + strlen(sourse);
}

size_t __getdelim(char **lineptr, size_t *n, int delim, FILE *stream) {
    TRACE_SYMBOL_CALL();
    return getdelim(lineptr, n, delim, stream);
}

unsigned long int pthread_self(void) {
    TRACE_SYMBOL_CALL();
    return (unsigned long int)get_thread_data();
}

#define POOL_LOG(fmt, ...) printf("[pool] " fmt, ##__VA_ARGS__)

/* Worker lifecycle counters. Non-static so wrapper can print them at destroy time. */
volatile long g_pt_created = 0;
volatile long g_pt_destroyed = 0;
volatile long g_pt_getstack = 0;

int pthread_attr_init(pthread_attr *attr) {
    TRACE_SYMBOL_CALL();
    long n = __sync_add_and_fetch(&g_pt_created, 1);
    POOL_LOG("pthread_attr_init#%ld created=%ld destroyed=%ld in_flight=%ld\n",
             n, g_pt_created, g_pt_destroyed,
             g_pt_created - g_pt_destroyed);
    return 0;
}

int pthread_setname_np() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_attr_setdetachstate(pthread_attr *attr, int detachstate) {
    TRACE_SYMBOL_CALL();
    return 0;
}

/*
 * Return the real TCS stack bounds for the calling thread.
 */
int pthread_attr_getstack(const pthread_attr *a, void ** addr, size_t *size) {
    TRACE_SYMBOL_CALL();
    thread_data *self = (thread_data *)get_thread_data();
    uint64_t stack_base_addr = self->__stack_base_addr;
    uint64_t stack_limit_addr = self->__stack_limit_addr;

    *size = (int)ROUND_TO_PAGE(stack_base_addr - stack_limit_addr);
    *addr = (void *)stack_limit_addr;
    long n = __sync_add_and_fetch(&g_pt_getstack, 1);
    POOL_LOG("pthread_attr_getstack#%ld tcs_self=%p"
             " created=%ld destroyed=%ld in_flight=%ld\n",
             n, (void*)self, g_pt_created, g_pt_destroyed,
             g_pt_created - g_pt_destroyed);
    return 0;
}

int pthread_attr_getguardsize(const pthread_attr *a, size_t *size) {
    TRACE_SYMBOL_CALL();
    *size = 1;
    return 0;
}

int getrlimit(int resource, rlimit* rlim) {
	TRACE_SYMBOL_CALL();
    int ret = 0;
    ocall_getrlimit(&ret, resource, (void*)rlim);
    return ret;
}

int mprotect() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_condattr_init() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_condattr_setclock() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_cond_timedwait() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_getattr_np() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_attr_setstacksize() {
    TRACE_SYMBOL_CALL();
    return 0;
}

int pthread_attr_destroy() {
    TRACE_SYMBOL_CALL();
    long n = __sync_add_and_fetch(&g_pt_destroyed, 1);
    thread_data *self = (thread_data *)get_thread_data();
    POOL_LOG("pthread_attr_destroy#%ld tcs_self=%p created=%ld destroyed=%ld"
             " in_flight=%ld\n",
             n, (void*)self, g_pt_created, g_pt_destroyed,
             g_pt_created - g_pt_destroyed);
    return 0;
}

int setrlimit() {
    TRACE_SYMBOL_CALL();
    return 0;
}
