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

package org.apache.teaclave.javasdk.enclave;

import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.PointerBase;

/**
 * Per-TCS IsolateThread cache and CallBacks storage via {@code @CFunction(NO_TRANSITION)}.
 * <p>
 * Each SGX TCS gets its own IsolateThread (created lazily via enterAttachThread on first
 * ECALL, cached by TCS identity). This ensures OSThreadIdTL always matches the actual
 * executing thread, which is required for correct GC safepoint coordination.
 * <p>
 * Also provides per-TCS CallBacks storage, replacing the shared static volatile
 * {@code callBackMethods} field in {@link EnclaveEntry} to prevent dangling-pointer
 * races during concurrent ECALLs.
 * <p>
 * All methods are safe to call from {@code @Uninterruptible} prologue/epilogue code
 * and from normal Java code.
 */
final class NativeTcsCache {
    private NativeTcsCache() {}

    /** Look up the cached IsolateThread for the calling TCS. Returns handle or 0. */
    @CFunction(value = "tee_sdk_tcs_lookup", transition = CFunction.Transition.NO_TRANSITION)
    static native long lookup();

    /** Register a newly created IsolateThread for the calling TCS. */
    @CFunction(value = "tee_sdk_tcs_register", transition = CFunction.Transition.NO_TRANSITION)
    static native void register(long isolateThread);

    /** Check if TCS cache mode is active. Returns non-zero if initialized. */
    @CFunction(value = "tee_sdk_tcs_is_initialized", transition = CFunction.Transition.NO_TRANSITION)
    static native int isInitialized();

    /** Store the CallBacks pointer for the calling TCS. */
    @CFunction(value = "tee_sdk_tcs_set_callbacks", transition = CFunction.Transition.NO_TRANSITION)
    static native void setCallbacks(PointerBase callbacks);

    /** Retrieve the CallBacks pointer for the calling TCS. */
    @CFunction(value = "tee_sdk_tcs_get_callbacks", transition = CFunction.Transition.NO_TRANSITION)
    static native PointerBase getCallbacks();

    /** Debug logging — write a C string to stdout with pool tag prefix. */
    @CFunction(value = "tee_sdk_pool_log", transition = CFunction.Transition.NO_TRANSITION)
    static native void log(CCharPointer msg);
}
