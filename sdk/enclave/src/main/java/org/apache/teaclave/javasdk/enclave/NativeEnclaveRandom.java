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
import org.graalvm.nativeimage.c.type.VoidPointer;

/**
 * In-enclave entropy source backed by {@code sgx_read_rand} (RDRAND).
 * <p>
 * Used by {@code NativePRNGSubstitutions.readFully} to fetch entropy without
 * traversing the {@code callbacks_t.get_random_number} OCALL indirection.
 * That path is hostile to GraalVM's safepoint model under concurrent ECALLs:
 * it requires a per-TCS callbacks lookup and exposes the calling
 * thread to a safepoint window. 
 * <p>
 * RDRAND runs entirely inside the enclave (no OCALL = no callbacks indirection)
 */
public final class NativeEnclaveRandom {
    private NativeEnclaveRandom() {}

    /**
     * Fill {@code buf} with {@code size} random bytes from {@code sgx_read_rand}.
     * Returns 0 on success, non-zero {@code sgx_status_t} otherwise.
     */
    @CFunction(value = "tee_sdk_enclave_read_rand", transition = CFunction.Transition.NO_TRANSITION)
    public static native int readRand(VoidPointer buf, int size);
}
