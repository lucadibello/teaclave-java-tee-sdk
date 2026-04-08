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

/**
 * C-side lock-free IsolateThread pool access via {@code @CFunction(NO_TRANSITION)}.
 * <p>
 * Claim returns a free IsolateThread handle (or 0 if exhausted).
 * Release returns a handle to the pool.
 * Both are safe to call from {@code @Uninterruptible} prologue/epilogue code.
 */
final class NativePool {
    private NativePool() {}

    @CFunction(value = "tee_sdk_pool_claim", transition = CFunction.Transition.NO_TRANSITION)
    static native long claim();

    @CFunction(value = "tee_sdk_pool_release", transition = CFunction.Transition.NO_TRANSITION)
    static native void release(long threadHandle);
}
