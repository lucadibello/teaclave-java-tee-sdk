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

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <sgx_trts.h>
#include <sgx_thread.h>

#include <graal_isolate.h>
#include <enc_environment.h>
#include <enc_exported_symbol.h>

#include "tee_sdk_symbol.h"
#include "tee_sdk_wrapper.h"

typedef int (*enclave_calling_stub)(uint64_t isolate, enc_data_t* input, enc_data_t* output, callbacks_t* callback);

char* alloc_memory_from_host(char* src, int len) {
    int flag = 0;
    char *ptr = 0;
    ocall_malloc(&flag, len, (void*)&ptr);
    // ocall malloc buffer failed.
    if (flag != 0x0) { return NULL; }
    memcpy(ptr, src, len);
    return (char*)ptr;
}

void tee_sdk_exception_callback(char* err_msg, char* stack_trace, char* exception_name) {
    printf("err_msg=%s\n", err_msg);
    printf("stack_trace=%s\n", stack_trace);
    printf("exception_name=%s\n", exception_name);
}

int tee_sdk_random(void* data, long size) {
    return (int)sgx_read_rand(data, (size_t)size);
}

int enclave_svm_isolate_create(void* isolate, void* isolateThread, int flag, char* args) {
    graal_isolate_t* isolate_t;
    graal_isolatethread_t* thread_t;

    // Implicitly set graal_create_isolate_params_t param as NULL.
    enable_trace_symbol_calling = flag;
    int argc = 2;
    char* parameters[2];
    parameters[0] = NULL;
    parameters[1] = args;
    int ret = create_isolate_with_params(argc, parameters, &isolate_t, &thread_t);
    *(uint64_t*)isolate = (uint64_t)isolate_t;
    *(uint64_t*)isolateThread = (uint64_t)thread_t;
    return ret;
}

int enclave_svm_isolate_destroy(uint64_t isolateThread) {
    return graal_detach_all_threads_and_tear_down_isolate((graal_isolatethread_t*)isolateThread);
}

int enclave_svm_calling_entry(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length, enclave_calling_stub stub) {
    enc_data_t request;
    enc_data_t response;

    request.data = (char*) input;
    request.data_len = input_length;
    response.data = NULL;
    response.data_len = 0x0;

    callbacks_t callback_methods;
    callback_methods.memcpy_char_pointer = &alloc_memory_from_host;
    callback_methods.exception_handler = &tee_sdk_exception_callback;
    callback_methods.get_random_number = &tee_sdk_random;

    int ret = stub(isolate, &request, &response, &callback_methods);
    if(ret != 0) { return ret; }

    *(int64_t*)output = (int64_t)response.data;
    *output_length = response.data_len;

    return 0x0;
}

int load_enclave_svm_services(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_loadservice_invoke);
}

int invoke_enclave_svm_service(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_enclave_invoke);
}

int unload_enclave_svm_service(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_unloadservice_invoke);
}

/*
 * IsolateThread pool pre-allocation for multi-thread support.
 *
 * Spawns N helper pthreads inside the enclave. Each helper calls
 * java_attach_helper_thread which triggers enterAttachThread (via EnclavePrologue,
 * since the pool is not yet initialized), registering a new IsolateThread for
 * that OS thread. The helper then parks permanently on a condition variable,
 * keeping its OS thread alive so GraalVM's thread list stays consistent (the
 * IsolateThread's OSThreadIdTL remains valid).
 *
 * After all helpers have registered, the C-side lock-free pool is initialized
 * with the handles. ECALL threads claim a free handle via CAS on a bitmask,
 * enter via CEntryPointActions.enter(), and release back on exit.
 *
 * IMPORTANT: Each parked helper holds one TCS slot. The pool size must be
 * smaller than enclave_max_thread to leave TCS slots free for actual ECALL
 * entry threads and any internal Java threads (e.g., Thread.start() inside
 * the enclave). The caller is responsible for passing an appropriate
 * thread_count that leaves sufficient TCS headroom.
 *
 * Threading note: We use SGX-native sgx_thread_mutex_t / sgx_thread_cond_t
 * (from sgx_thread.h) instead of POSIX types to avoid header conflicts with
 * tee_sdk_symbol.h's pthread_self() stub declaration.
 */

/* Forward-declare pthread functions — provided by SGX's tlibc pthread shim
 * (backed by sgx_pthread.edl OCALLs). We cannot include <pthread.h> because
 * tee_sdk_symbol.h declares pthread_self() with an incompatible signature. */
#ifndef _PTHREAD_H_
struct _pthread;
typedef struct _pthread *pthread_t;
struct _pthread_attr;
typedef struct _pthread_attr *pthread_attr_t;
int pthread_create(pthread_t *, const pthread_attr_t *,
                   void *(*)(void *), void *);
int pthread_join(pthread_t, void **);
#endif

typedef struct {
    uint64_t isolate;              /* The isolate to attach to */
    uint64_t thread_handle;        /* Output: the registered IsolateThread handle */
    int done;                      /* Flag: 1 when registration is complete */
    volatile int shutdown;         /* Flag: 1 signals the helper to exit */
    sgx_thread_mutex_t mutex;
    sgx_thread_cond_t cond;
    sgx_thread_cond_t park_cond;   /* Condition for parking / shutdown wakeup */
} helper_thread_ctx_t;

/* Global state for the pool helpers so we can unpark them during destroy. */
static helper_thread_ctx_t* g_pool_contexts = NULL;
static pthread_t* g_pool_threads = NULL;
static int g_pool_count = 0;

/*
 * Lock-free IsolateThread pool managed in C.
 *
 * The Java prologue cannot use KnownIntrinsics or complex Java APIs from
 * @Uninterruptible code. Instead, the prologue calls into C via
 * @CFunction(NO_TRANSITION) to claim/release pool slots.
 *
 * Design: a bitmask where bit i == 1 means slot i is free. Claim clears a
 * bit via CAS; release sets it via CAS. Up to 64 slots (single uint64_t).
 */
#define MAX_POOL_SLOTS 64
static uint64_t g_pool_handles[MAX_POOL_SLOTS];
static volatile uint64_t g_pool_free_mask = 0;
static int g_pool_size = 0;
static volatile int g_pool_initialized = 0;

/* Flag for wide stack bounds in pthread_attr_getstack (tee_sdk_symbol.c) */
volatile int g_use_wide_stack_bounds = 0;

/*
 * Called from the Java prologue (@CFunction NO_TRANSITION) to claim a free
 * IsolateThread handle from the pool. Returns the handle, or 0 if exhausted.
 */
uint64_t tee_sdk_pool_claim(void) {
    while (1) {
        uint64_t current = g_pool_free_mask;
        if (current == 0) {
            return 0; /* Pool exhausted */
        }
        /* Find lowest set bit */
        int index = __builtin_ctzll(current);
        uint64_t updated = current & ~(1ULL << index);
        if (__sync_bool_compare_and_swap(&g_pool_free_mask, current, updated)) {
            return g_pool_handles[index];
        }
        /* CAS failed — retry */
    }
}

/*
 * Called from the Java epilogue (@CFunction NO_TRANSITION) to release an
 * IsolateThread handle back to the pool.
 */
void tee_sdk_pool_release(uint64_t thread_handle) {
    /* Find the index of this handle */
    for (int i = 0; i < g_pool_size; i++) {
        if (g_pool_handles[i] == thread_handle) {
            uint64_t bit = 1ULL << i;
            while (1) {
                uint64_t current = g_pool_free_mask;
                uint64_t updated = current | bit;
                if (__sync_bool_compare_and_swap(&g_pool_free_mask, current, updated)) {
                    return;
                }
            }
        }
    }
}

/*
 * Initialize the C-side pool. Called from enclave_svm_preallocate_threads
 * after all helpers have registered.
 */
static void init_c_pool(int count, uint64_t* handles) {
    if (count > MAX_POOL_SLOTS) {
        count = MAX_POOL_SLOTS;
    }
    for (int i = 0; i < count; i++) {
        g_pool_handles[i] = handles[i];
    }
    g_pool_size = count;

    uint64_t mask;
    if (count >= 64) {
        mask = ~0ULL;
    } else {
        mask = (1ULL << count) - 1ULL;
    }
    __sync_synchronize();
    g_pool_free_mask = mask;
    g_pool_initialized = 1;
}

static void* helper_thread_func(void* arg) {
    helper_thread_ctx_t* ctx = (helper_thread_ctx_t*)arg;

    /*
     * Call the Java entry point which triggers EnclavePrologue.enter().
     * Since the pool is NOT yet initialized, the prologue falls back to
     * enterAttachThread, which registers a new IsolateThread for this pthread.
     * The entry point writes the IsolateThread handle to ctx->thread_handle.
     */
    uint64_t handle_out = 0;
    int ret = java_attach_helper_thread((graal_isolate_t*)ctx->isolate, (void*)&handle_out);

    sgx_thread_mutex_lock(&ctx->mutex);
    if (ret == 0) {
        ctx->thread_handle = handle_out;
    }
    ctx->done = 1;
    sgx_thread_cond_signal(&ctx->cond);

    /* Park until shutdown is signaled. Keeps the OS thread alive so the
     * IsolateThread's OSThreadIdTL remains valid. */
    while (!ctx->shutdown) {
        sgx_thread_cond_wait(&ctx->park_cond, &ctx->mutex);
    }
    sgx_thread_mutex_unlock(&ctx->mutex);

    /* Detach the IsolateThread from GraalVM's thread list before exiting.
     * This calls enter(thread) + leaveDetachThread() which removes the
     * IsolateThread from the linked list and frees it, so isolate teardown
     * won't hang waiting for this zombie thread. */
    if (ctx->thread_handle != 0) {
        java_detach_helper_thread((graal_isolatethread_t*)ctx->thread_handle);
    }
    return NULL;
}

int enclave_svm_preallocate_threads(uint64_t isolate, int thread_count) {
    if (thread_count <= 0) {
        return 0;
    }
    /* Cap at a reasonable maximum */
    if (thread_count > 64) {
        thread_count = 64;
    }

    /* Enable wide stack bounds BEFORE spawning helpers.
     * Each helper calls enterAttachThread → StackOverflowCheck.initialize()
     * → pthread_attr_getstack(). With this flag set, pthread_attr_getstack
     * will track the global min/max across all TCS stacks, so every
     * IsolateThread gets bounds covering ALL TCS stacks. */
    g_use_wide_stack_bounds = 1;

    /* Allocate context structs and handle array */
    helper_thread_ctx_t* contexts = (helper_thread_ctx_t*)malloc(
        thread_count * sizeof(helper_thread_ctx_t));
    if (!contexts) {
        return -1;
    }
    uint64_t* handles = (uint64_t*)malloc(thread_count * sizeof(uint64_t));
    if (!handles) {
        free(contexts);
        return -1;
    }

    pthread_t* threads = (pthread_t*)malloc(thread_count * sizeof(pthread_t));
    if (!threads) {
        free(handles);
        free(contexts);
        return -1;
    }

    /* Spawn helper pthreads */
    int spawned = 0;
    for (int i = 0; i < thread_count; i++) {
        contexts[i].isolate = isolate;
        contexts[i].thread_handle = 0;
        contexts[i].done = 0;
        contexts[i].shutdown = 0;
        sgx_thread_mutex_init(&contexts[i].mutex, NULL);
        sgx_thread_cond_init(&contexts[i].cond, NULL);
        sgx_thread_cond_init(&contexts[i].park_cond, NULL);

        int err = pthread_create(&threads[i], NULL, helper_thread_func, &contexts[i]);
        if (err != 0) {
            printf("[enclave] Failed to create helper thread %d, error=%d\n", i, err);
            break;
        }
        spawned++;
    }

    /* Wait for all spawned helpers to complete registration */
    int registered = 0;
    for (int i = 0; i < spawned; i++) {
        sgx_thread_mutex_lock(&contexts[i].mutex);
        while (!contexts[i].done) {
            sgx_thread_cond_wait(&contexts[i].cond, &contexts[i].mutex);
        }
        if (contexts[i].thread_handle != 0) {
            handles[registered] = contexts[i].thread_handle;
            registered++;
        }
        sgx_thread_mutex_unlock(&contexts[i].mutex);
    }

    printf("[enclave] Pre-allocated %d IsolateThread handles (requested %d)\n",
           registered, thread_count);

    /* Initialize both the Java-side pool (for isInitialized() flag) and the
     * C-side lock-free pool (for actual claim/release from the prologue). */
    if (registered > 0) {
        int ret = java_init_thread_pool(
            (graal_isolate_t*)isolate,
            registered,
            (void*)handles,
            (long)(registered * sizeof(uint64_t)));
        if (ret != 0) {
            printf("[enclave] java_init_thread_pool failed with code %d\n", ret);
            free(handles);
            free(threads);
            /* Don't free contexts — helper threads still reference them */
            return ret;
        }
        init_c_pool(registered, handles);
    }

    printf("[enclave] C-side pool initialized with %d slots\n", registered);

    /* Save global references for shutdown */
    g_pool_contexts = contexts;
    g_pool_threads = threads;
    g_pool_count = spawned;

    free(handles);
    /* Don't free contexts or threads — needed for shutdown */

    return 0;
}

/*
 * Unpark all helper pthreads, wait for them to fully detach their
 * IsolateThreads, and clean up.
 *
 * Must be called BEFORE graal_detach_all_threads_and_tear_down_isolate.
 * Each helper calls java_detach_helper_thread (enter + leaveDetachThread)
 * which removes its IsolateThread from GraalVM's thread list. We must
 * pthread_join every helper to guarantee all detaches have completed
 * before isolate teardown begins — otherwise teardown hangs waiting for
 * threads that are still mid-detach.
 */
void enclave_svm_release_pool_threads(void) {
    if (g_pool_contexts == NULL || g_pool_count == 0) {
        return;
    }

    int count = g_pool_count;

    /* Signal all helpers to shut down */
    for (int i = 0; i < count; i++) {
        sgx_thread_mutex_lock(&g_pool_contexts[i].mutex);
        g_pool_contexts[i].shutdown = 1;
        sgx_thread_cond_signal(&g_pool_contexts[i].park_cond);
        sgx_thread_mutex_unlock(&g_pool_contexts[i].mutex);
    }

    printf("[enclave] Signaled %d pool helper threads to shut down\n", count);

    /* Join all helpers — blocks until each has called java_detach_helper_thread
     * and exited. This guarantees the GraalVM thread list is drained of all
     * pool IsolateThreads before isolate teardown starts. */
    for (int i = 0; i < count; i++) {
        pthread_join(g_pool_threads[i], NULL);
    }

    printf("[enclave] All %d pool helper threads have exited\n", count);

    /* Safe to free now — all helpers have exited */
    free(g_pool_contexts);
    free(g_pool_threads);
    g_pool_contexts = NULL;
    g_pool_threads = NULL;
    g_pool_count = 0;
}