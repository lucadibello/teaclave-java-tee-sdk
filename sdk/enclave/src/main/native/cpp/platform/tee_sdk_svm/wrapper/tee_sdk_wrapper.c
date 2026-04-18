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

/*
 * In-enclave entropy fetch for NativePRNGSubstitutions.readFully().
 *
 * Called via @CFunction(NO_TRANSITION) directly from Java, bypassing
 * the callbacks_t.get_random_number indirection. This matters under
 * concurrent ECALLs: dispatching through callbacks_t requires a
 * per-TCS callbacks lookup AND historically left the calling thread
 * exposed to a safepoint window via the host-facing function pointer
 * shape. sgx_read_rand runs entirely inside the enclave (RDRAND),
 * so there is no OCALL and no safepoint hazard.
 *
 * Returns 0 on success (SGX_SUCCESS), non-zero sgx_status_t otherwise.
 */
int tee_sdk_enclave_read_rand(void* data, int size) {
    if (data == NULL || size <= 0) {
        return -1;
    }
    return (int)sgx_read_rand((unsigned char*)data, (size_t)size);
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
 * Per-TCS IsolateThread cache for multi-thread ECALL support.
 *
 * Each SGX TCS (Thread Control Structure) gets its own IsolateThread,
 * created lazily on the first ECALL via enterAttachThread() and cached
 * by TCS identity (pthread_self() → get_thread_data()). Subsequent
 * ECALLs on the same TCS reuse the cached IsolateThread via enter().
 *
 * This design ensures OSThreadIdTL always matches the actual executing
 * thread, which is required for correct GC safepoint coordination.
 * No helper pthreads are needed — the ECALL thread itself registers
 * its own IsolateThread.
 *
 * The cache also stores per-TCS CallBacks pointers, replacing the
 * shared static volatile callBackMethods field in EnclaveEntry.java.
 * This prevents a dangling-pointer race when concurrent ECALLs
 * overwrite each other's stack-local callbacks_t pointers.
 */

#define MAX_TCS_CACHE 64

typedef struct {
    unsigned long tcs_id;           /* pthread_self() value for this TCS */
    uint64_t      isolate_thread;   /* cached IsolateThread handle, or 0 */
    void*         callbacks;        /* per-TCS CallBacks pointer (to stack-local callbacks_t) */
} tcs_cache_entry_t;

static tcs_cache_entry_t g_tcs_cache[MAX_TCS_CACHE];
static volatile int g_tcs_cache_count = 0;
static volatile int g_tcs_cache_initialized = 0;

/* Flag for wide stack bounds in pthread_attr_getstack (tee_sdk_symbol.c) */
volatile int g_use_wide_stack_bounds = 0;

void tee_sdk_pool_log(const char* msg) {
    printf("[pool:java] %s\n", msg);
}

/*
 * Look up the cached IsolateThread for the calling TCS.
 * Called from the Java prologue via @CFunction(NO_TRANSITION).
 * Returns the handle, or 0 if this TCS has no cached entry yet.
 */
uint64_t tee_sdk_tcs_lookup(void) {
    unsigned long my_tcs = pthread_self();
    int count = g_tcs_cache_count;
    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].tcs_id == my_tcs) {
            return g_tcs_cache[i].isolate_thread;
        }
    }
    return 0;
}

/*
 * Register a newly created IsolateThread for the calling TCS.
 * Called from the Java prologue (after enterAttachThread) via
 * @CFunction(NO_TRANSITION). Thread-safe: each TCS can only be
 * here once (SGX guarantees one ECALL per TCS), but multiple TCSes
 * may register concurrently, so we use atomic increment for the index.
 */
void tee_sdk_tcs_register(uint64_t isolate_thread) {
    unsigned long my_tcs = pthread_self();
    int idx = __sync_fetch_and_add(&g_tcs_cache_count, 1);
    if (idx < MAX_TCS_CACHE) {
        g_tcs_cache[idx].tcs_id = my_tcs;
        g_tcs_cache[idx].isolate_thread = isolate_thread;
        g_tcs_cache[idx].callbacks = NULL;
        __sync_synchronize();
    }
}

/*
 * Check if TCS cache mode is active.
 * Called from the Java prologue via @CFunction(NO_TRANSITION).
 */
int tee_sdk_tcs_is_initialized(void) {
    return g_tcs_cache_initialized;
}

/*
 * Store the CallBacks pointer for the calling TCS.
 * Replaces the shared static volatile callBackMethods in EnclaveEntry.java.
 * Called from normal Java code via @CFunction(NO_TRANSITION).
 */
void tee_sdk_tcs_set_callbacks(void* cb) {
    unsigned long my_tcs = pthread_self();
    int count = g_tcs_cache_count;
    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].tcs_id == my_tcs) {
            g_tcs_cache[i].callbacks = cb;
            return;
        }
    }
}

/*
 * Retrieve the CallBacks pointer for the calling TCS.
 * Called from NativePRNGSubstitutions.readFully() and
 * EnclaveEntry.handleFrameworkException() via @CFunction(NO_TRANSITION).
 */
void* tee_sdk_tcs_get_callbacks(void) {
    unsigned long my_tcs = pthread_self();
    int count = g_tcs_cache_count;
    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].tcs_id == my_tcs) {
            return g_tcs_cache[i].callbacks;
        }
    }
    return NULL;
}

/*
 * Initialize TCS cache mode.
 *
 * Sets the wide stack bounds flag so that enterAttachThread →
 * StackOverflowCheck → pthread_attr_getstack returns bounds covering
 * the entire user-space address range. This allows IsolateThreads
 * created from any TCS to pass stack overflow checks.
 *
 * No helper pthreads are spawned. IsolateThreads are created lazily
 * by ECALL threads on first entry.
 */
int enclave_svm_preallocate_threads(uint64_t isolate, int thread_count) {
    (void)isolate;
    (void)thread_count;

    /* Enable wide stack bounds BEFORE any TCS-cache-mode ECALL. */
    g_use_wide_stack_bounds = 1;
    __sync_synchronize();

    /* Signal Java prologue to use TCS cache mode */
    g_tcs_cache_initialized = 1;
    __sync_synchronize();

    printf("[enclave] TCS cache mode initialized (wide stack bounds enabled)\n");
    return 0;
}

/*
 * Detach all cached IsolateThreads from GraalVM's thread list.
 *
 * Must be called BEFORE graal_detach_all_threads_and_tear_down_isolate
 * and after all ECALL activity has stopped. Each cached IsolateThread
 * is in STATUS_IN_NATIVE (from leave()). We call java_detach_helper_thread
 * which does enter(thread) + leaveDetachThread() to remove it from
 * GraalVM's thread list. The brief OSThreadIdTL mismatch during enter()
 * is safe because leaveDetachThread immediately removes the thread —
 * no GC can trigger in that window.
 */
void enclave_svm_release_pool_threads(void) {
    int count = g_tcs_cache_count;
    if (count == 0) return;

    printf("[enclave] Detaching %d cached IsolateThreads\n", count);

    /* Prevent new ECALLs from using the cache */
    g_tcs_cache_initialized = 0;
    __sync_synchronize();

    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].isolate_thread != 0) {
            java_detach_helper_thread(
                (graal_isolatethread_t*)g_tcs_cache[i].isolate_thread);
            g_tcs_cache[i].isolate_thread = 0;
        }
    }
    g_tcs_cache_count = 0;
    printf("[enclave] All cached IsolateThreads detached\n");
}