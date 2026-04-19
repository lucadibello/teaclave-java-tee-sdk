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

/**
 * Allocate memory in the host and copy data from the enclave to the allocated buffer. This is used for callbacks that need to return data to the host.
 */
char* alloc_memory_from_host(char* src, int len) {
    int flag = 0;
    char *ptr = 0;
    ocall_malloc(&flag, len, (void*)&ptr);
    // ocall malloc buffer failed.
    if (flag != 0x0) { return NULL; }
    memcpy(ptr, src, len);
    return (char*)ptr;
}

/**
 * Callback for java exceptions. Prints the error message, stack trace,
 * and exception name to the enclave's stdout for debugging purposes.
 */
void tee_sdk_exception_callback(char* err_msg, char* stack_trace, char* exception_name) {
    printf("err_msg=%s\n", err_msg);
    printf("stack_trace=%s\n", stack_trace);
    printf("exception_name=%s\n", exception_name);
}

/**
 * Callback for random number generation. Uses the SGX SDK's
 * sgx_read_rand function to fill the provided buffer with random bytes.
 * Returns 0 on success, non-zero on failure.
 */
int tee_sdk_random(void* data, long size) {
    return (int)sgx_read_rand(data, (size_t)size);
}

/**
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

/**
 * Creates a new Graal isolate and its primary thread, and returns their handles to the caller
 * to be used for subsequent ECALLs into the enclave.
 * Returns 0 on success, non-zero on failure.
 * NOTE: The caller is responsible for eventually destroying the isolate via enclave_svm_isolate_destroy.
 */
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

/** Forward declarations for diagnostics in enclave_svm_isolate_destroy -
 * cache state is defined further down; declare its accessors here. */
int tee_sdk_tcs_cache_count(void);
int tee_sdk_tcs_cache_initialized(void);
void tee_sdk_tcs_cache_dump(const char* tag);

/* Forward decls to diagnostics counters defined in tee_sdk_symbol.c */
extern volatile long g_pt_created;
extern volatile long g_pt_destroyed;

/**
 * Destroy the isolate, safely supporting the case where destroy runs on
 * a different host OS thread than the one that created the enclave.
 *
 * Falls back to the upstream one-thread behaviour if graal_attach_thread
 * fails.
 */
int enclave_svm_isolate_destroy(uint64_t isolateThread) {
    printf("[destroy] begin: primary=0x%lx cache_count=%d cache_init=%d"
           " pt_created=%ld pt_destroyed=%ld in_flight=%ld\n",
           isolateThread, tee_sdk_tcs_cache_count(), tee_sdk_tcs_cache_initialized(),
           g_pt_created, g_pt_destroyed, g_pt_created - g_pt_destroyed);

    // FIXME: to be removed once the destruction path is stable
    tee_sdk_tcs_cache_dump("destroy");
    fflush(stdout);

    // get the isolate from the primary thread's IsolateThread handle (primary thread = thread that created the enclave)
    graal_isolatethread_t* primary = (graal_isolatethread_t*)isolateThread;
    graal_isolate_t* isolate = graal_get_isolate(primary);
    if (isolate == NULL) {
        printf("[destroy] isolate==NULL; fallback to primary tear_down\n"); fflush(stdout);
        int r = graal_detach_all_threads_and_tear_down_isolate(primary);
        printf("[destroy] tear_down(primary) returned %d\n", r); fflush(stdout);
        return r;
    }

    // attach to primary thread's isolate
    graal_isolatethread_t* my_thread = NULL;
    int rc = graal_attach_thread(isolate, &my_thread);
    printf("[destroy] graal_attach_thread rc=%d my_thread=%p\n", rc, (void*)my_thread); fflush(stdout);

    // if attach fails, fallback to tearing down from the primary thread's IsolateThread (default behavior)
    if (rc != 0 || my_thread == NULL) {
        int r = graal_detach_all_threads_and_tear_down_isolate(primary);
        printf("[destroy] tear_down(primary) returned %d\n", r); fflush(stdout);
        return r;
    }

    // if attach succeeds, tear down from the attached thread (supports multi-threaded ECALLs on destroy)
    printf("[destroy] calling tear_down(my_thread=%p)...\n", (void*)my_thread); fflush(stdout);
    int r = graal_detach_all_threads_and_tear_down_isolate(my_thread);
    printf("[destroy] tear_down returned %d\n", r); fflush(stdout);
    return r;
}

/**
 * Shared logic for enclave SVM service calls (load/invoke/unload). Prepares the request and response structures, populates the callbacks, and dispatches to the provided stub.
 */
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

/**
 * ECALL entry point for loading an enclave SVM service. Delegates to enclave_svm_calling_entry with the java_loadservice_invoke stub.
 */
int load_enclave_svm_services(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_loadservice_invoke);
}

/**
 * ECALL entry point for invoking an enclave SVM service. Delegates to enclave_svm_calling_entry with the java_enclave_invoke stub.
 */
int invoke_enclave_svm_service(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_enclave_invoke);
}

/**
 * ECALL entry point for unloading an enclave SVM service. Delegates to enclave_svm_calling_entry with the java_unloadservice_invoke stub.
 */
int unload_enclave_svm_service(uint64_t isolate, void* input, size_t input_length, void* output, size_t* output_length) {
    return enclave_svm_calling_entry(isolate, input, input_length, output, output_length, (enclave_calling_stub)java_unloadservice_invoke);
}

/**
 * MAX_TCS_CACHE must be large enough to accommodate the number of
 * concurrent (tcs_id, host_thread_id) pairs that can exist during the enclave's lifetime
 * (e.g. concurrent ECALLs from multiple host threads on the same TCS, or concurrent ECALLs
 * on different TCSes)
 *
 * NOTE: since we recycle TCS slots by invalidating stale entries on reuse,
 * MAX_TCS_CACHE does not need to be as large as the total number of threads
 * that ever enter the enclave, but only the maximum number of concurrent threads
 * that can be executing ECALLs at the same time.
 */
#define MAX_TCS_CACHE 256

/*
 * Per-TCS IsolateThread cache for multi-thread ECALL support.
 *
 * Each SGX TCS (Thread Control Structure) gets its own IsolateThread,
 * created lazily on the first ECALL via enterAttachThread() and cached
 * by TCS identity (pthread_self() -> get_thread_data()). Subsequent
 * ECALLs on the same TCS reuse the cached IsolateThread via enter().
 *
 * This design ensures OSThreadIdTL always matches the actual executing
 * thread, which is required for correct GC safepoint coordination.
 *
 * The cache also stores per-TCS CallBacks pointers which are associated
 * with the IsolateThread and lazily populated by ECALL prologues
 */
typedef struct {
    unsigned long tcs_id;           /* pthread_self() value for this TCS (per-TCS slot identity) */
    uint64_t      host_thread_id;   /* host-side pthread_self() of the OS thread that created this entry */
    uint64_t      isolate_thread;   /* cached IsolateThread handle, or 0 if slot is stale/empty */
    void*         callbacks;        /* per-TCS CallBacks pointer (to stack-local callbacks_t) */
} tcs_cache_entry_t;

static tcs_cache_entry_t g_tcs_cache[MAX_TCS_CACHE];
static volatile int g_tcs_cache_count = 0;
static volatile int g_tcs_cache_initialized = 0;

/* Diagnostics */

int tee_sdk_tcs_cache_count(void) { return g_tcs_cache_count; }
int tee_sdk_tcs_cache_initialized(void) { return g_tcs_cache_initialized; }
void tee_sdk_tcs_cache_dump(const char* tag) {
    int count = g_tcs_cache_count;
    if (count > MAX_TCS_CACHE) count = MAX_TCS_CACHE;
    for (int i = 0; i < count; i++) {
        printf("[%s] cache[%d]: tcs=0x%lx host_tid=0x%lx it=0x%lx cb=%p\n",
               tag, i, g_tcs_cache[i].tcs_id, g_tcs_cache[i].host_thread_id,
               g_tcs_cache[i].isolate_thread, g_tcs_cache[i].callbacks);
    }
}

/* OCALL generated by edger8r in tee_sdk_enclave_t.h. Forward-declared here
 * because the wrapper does not include that header directly. */
extern sgx_status_t SGX_CDECL ocall_host_thread_id(uint64_t* retval);

/*
* Host OS thread id for the currently executing ECALL. Obtained via OCALL
* (ocall_host_thread_id -> pthread_self() on host). Used to detect TCS slot
* recycling: if the same TCS is entered by a different host OS thread than
* the one that originally attached its IsolateThread, the cached entry is
* stale (OSThreadIdTL refers to the old host thread).
*/
static uint64_t current_host_thread_id(void) {
    uint64_t host_tid = 0;
    ocall_host_thread_id(&host_tid);
    return host_tid;
}

/*
 * Look up a cached IsolateThread for the calling (TCS, host-thread) pair.
 *
 * If the TCS has a cached entry but the host OS thread has changed (TCS slot
 * recycled by a different host thread), the stale entry is detached via
 * java_detach_helper_thread and zeroed out, and we return 0 so the prologue
 * takes the slow path and creates a fresh IsolateThread for the new host
 * thread.
 *
 * Returns the IsolateThread handle on hit, or 0 on miss / stale-and-evicted.
 */
uint64_t tee_sdk_tcs_lookup(void) {
    unsigned long my_tcs = pthread_self();
    uint64_t my_host_tid = current_host_thread_id();
    int count = g_tcs_cache_count;
    if (count > MAX_TCS_CACHE) count = MAX_TCS_CACHE;
    for (int i = 0; i < count; i++) {
        // check if the TCS matches with a cached entry
        if (g_tcs_cache[i].tcs_id == my_tcs && g_tcs_cache[i].isolate_thread != 0) {

            // if also the host thread matches, it's a cache hit: return the cached IsolateThread
            if (g_tcs_cache[i].host_thread_id == my_host_tid) {
                return g_tcs_cache[i].isolate_thread;
            }

            /*
             Otherwise, stale entry (same TCS but a different host thread means
             that the cached IsolateThread belongs to a now-dead host thread):
             invalidate the entry so the caller creates a new one for the new host thread

             By invalidating the cache entry here, on next ECALL on the same TCS the prologue takes
             the slow path and creates a fresh IsolateThread for the new host thread.
            */
            g_tcs_cache[i].isolate_thread = 0;
            g_tcs_cache[i].callbacks = NULL;

            // ensure all cores see the updated cache entry before any thread can hit it again
            __sync_synchronize();
            return 0;
        }
    }
    return 0;
}

/*
 * Register a newly created IsolateThread for the calling (TCS, host-thread)
 * pair. Claims a free slot via CAS so that overflow is detected without
 * corrupting the count: if no slot is available the cache remains untouched
 * and the caller falls back to the legacy path (enterAttachThread every
 * ECALL, no cache fast-path).
 *
 * Returns non-zero on success, 0 if the cache is full.
 */
int tee_sdk_tcs_register(uint64_t isolate_thread) {
    unsigned long my_tcs = pthread_self();
    uint64_t my_host_tid = current_host_thread_id();
    while (1) {
        // get the next cache index to claim
        int idx = g_tcs_cache_count;
        if (idx >= MAX_TCS_CACHE) { // if cache exhausted = force fallback to legacy path
            return 0; // failure
        }

        // CAS to claim the next cache slot (multiple threads might race to claim the same slot)
        if (__sync_bool_compare_and_swap(&g_tcs_cache_count, idx, idx + 1)) {
            // store the new entry in the claimed slot (idx)
            g_tcs_cache[idx].tcs_id = my_tcs;
            g_tcs_cache[idx].host_thread_id = my_host_tid;
            g_tcs_cache[idx].isolate_thread = isolate_thread;
            g_tcs_cache[idx].callbacks = NULL; // callbacks are lazily populated at each ECALL prologue
            __sync_synchronize(); // ensure all cores see the new cache entry before any thread can hit it

            return 1; // success
        }
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
 * Store the CallBacks pointer for the calling TCS. Matches on (tcs_id,
 * host_thread_id) + non-zero isolate_thread so a stale recycled entry does
 * not get its callbacks overwritten before the lookup path invalidates it.
 */
void tee_sdk_tcs_set_callbacks(void* cb) {
    // get identifier (tcs_id, host_thread_id)
    unsigned long my_tcs = pthread_self();
    uint64_t my_host_tid = current_host_thread_id();

    // get current cache capacity
    int count = g_tcs_cache_count;
    if (count > MAX_TCS_CACHE) count = MAX_TCS_CACHE;

    // linear scan entire TCS cache to find the matching entry (tcs_id, host_tread_id) with a valid isolate_thread
    // to update its callbacks pointer.
    // FIXME: here we might use an hashmap to avoid O(N) scan on every ECALL prologue
    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].tcs_id == my_tcs
            && g_tcs_cache[i].host_thread_id == my_host_tid
            && g_tcs_cache[i].isolate_thread != 0) {
            g_tcs_cache[i].callbacks = cb;
            return;
        }
    }
}

/*
 * Retrieve the CallBacks pointer for the calling TCS.
 */
void* tee_sdk_tcs_get_callbacks(void) {
    // get identifier (tcs_id, host_thread_id)
    unsigned long my_tcs = pthread_self();
    uint64_t my_host_tid = current_host_thread_id();

    // get current cache capacity
    int count = g_tcs_cache_count;
    if (count > MAX_TCS_CACHE) count = MAX_TCS_CACHE;

    // linear scan entire TCS cache to find the matching entry (tcs_id, host_tread_id) with a valid isolate_thread
    // to update its callbacks pointer.
    // FIXME: here we might use an hashmap to avoid O(N) scan on every ECALL prologue
    for (int i = 0; i < count; i++) {
        if (g_tcs_cache[i].tcs_id == my_tcs
            && g_tcs_cache[i].host_thread_id == my_host_tid
            && g_tcs_cache[i].isolate_thread != 0) {
            return g_tcs_cache[i].callbacks; // return the found callbacks pointer
        }
    }
    return NULL; // no match
}

/*
 * Initialize TCS cache mode.
 */
int enclave_svm_initialize_thread_cache(uint64_t isolate, int thread_count) {
    (void)isolate;
    (void)thread_count;

    // signal Java prologue to use TCS cache mode for subsequent ECALLs.
    // NOTE: the cache is empty at this point
    g_tcs_cache_initialized = 1;
    __sync_synchronize(); // ensure the intialized flag is visible by all cores

    printf("[enclave] TCS cache mode initialized\n");
    return 0;
}

/**
  * Release cached IsolateThreads in preparation for isolate teardown.
  * Each cached IsolateThread is still valid at this point (STATUS_IN_NATIVE
  * from last leave()), but we clear the cache entries so that any subsequent
  * ECALLs after shutdown do not reuse stale IsolateThread handles.
  *
  * NOTE: called from Java via @CFunction(NO_TRANSITION) to avoid safepoint hazards
  */
void enclave_svm_release_thread_cache(void) {
    int count = g_tcs_cache_count;
    if (count > MAX_TCS_CACHE) count = MAX_TCS_CACHE;

    /* Prevent new ECALLs from using the cache */
    g_tcs_cache_initialized = 0;
    __sync_synchronize();

    for (int i = 0; i < count; i++) {
        g_tcs_cache[i].isolate_thread = 0;
        g_tcs_cache[i].tcs_id = 0;
        g_tcs_cache[i].host_thread_id = 0;
        g_tcs_cache[i].callbacks = NULL;
    }
    g_tcs_cache_count = 0;

    printf("[enclave] TCS cache cleared (%d entries); teardown will detach via safepoint\n", count);
    fflush(stdout);
}
