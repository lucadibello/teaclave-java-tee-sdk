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

import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

/**
 * A pool of pre-registered {@link IsolateThread} handles for multi-threaded ECALL support.
 * <p>
 * TCS-based matching is performed in C code ({@code tee_sdk_find_thread_for_sp_auto}).
 * This Java-side pool only tracks:
 * <ul>
 *   <li>The initialized flag (checked from {@code @Uninterruptible} prologue code)</li>
 *   <li>The thread handles (for release-by-value lookup in the epilogue)</li>
 * </ul>
 * <p>
 * The pool is stored in native memory via {@link CGlobalData} so it is accessible from
 * {@code @Uninterruptible} prologue/epilogue code without touching the Java heap.
 */
public final class IsolateThreadPool {

    static final int MAX_POOL_SIZE = 64;

    /** IsolateThread handles, indexed 0..poolSize-1. */
    private static final CGlobalData<Pointer> threadHandles =
            CGlobalDataFactory.createBytes(() -> MAX_POOL_SIZE * Long.BYTES);

    /** Number of slots actually initialized. */
    private static final CGlobalData<Pointer> poolSize =
            CGlobalDataFactory.createBytes(() -> Integer.BYTES);

    /** 0 = not initialized, 1 = initialized. */
    private static final CGlobalData<Pointer> initialized =
            CGlobalDataFactory.createBytes(() -> Integer.BYTES);

    private IsolateThreadPool() {
    }

    /**
     * Initialize the pool with pre-registered IsolateThread handles.
     *
     * @param count   number of IsolateThread handles
     * @param handles pointer to array of IsolateThread values (as longs)
     */
    static void initialize(int count, Pointer handles) {
        if (count > MAX_POOL_SIZE) {
            count = MAX_POOL_SIZE;
        }

        Pointer slots = threadHandles.get();
        for (int i = 0; i < count; i++) {
            long threadValue = handles.readLong(i * Long.BYTES);
            slots.writeLong(i * Long.BYTES, threadValue);
        }

        poolSize.get().writeInt(0, count);
        initialized.get().writeInt(0, 1);
    }

    @Uninterruptible(reason = "Called from prologue")
    static boolean isInitialized() {
        return initialized.get().readInt(0) != 0;
    }

    /**
     * Release an IsolateThread back to the pool.
     * Currently a no-op for slot tracking since TCS-based matching is deterministic
     * (each TCS maps to exactly one IsolateThread), but kept for future use.
     */
    @Uninterruptible(reason = "Called from epilogue")
    static void release(IsolateThread thread) {
        // TCS-based matching is deterministic: each ECALL thread gets the same
        // IsolateThread every time based on its TCS stack. No bitmask needed.
    }
}
