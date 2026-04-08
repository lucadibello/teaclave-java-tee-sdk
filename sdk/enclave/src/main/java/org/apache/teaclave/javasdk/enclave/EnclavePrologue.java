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
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

public class EnclavePrologue implements CEntryPointOptions.Prologue {
    private static final CGlobalData<CCharPointer> errorMessage = CGlobalDataFactory.createCString("Failed to enter (or attach to) the global isolate in the current thread.");
    private static final CGlobalData<CCharPointer> poolExhaustedMessage = CGlobalDataFactory.createCString("IsolateThread pool exhausted: no free slots available for ECALL.");

    @Uninterruptible(reason = "prologue")
    static void enter(Isolate isolate) {
        if (IsolateThreadPool.isInitialized()) {
            // Claim a free IsolateThread from the C-side lock-free pool.
            long threadHandle = NativePool.claim();
            if (threadHandle == 0L) {
                CEntryPointActions.failFatally(1, poolExhaustedMessage.get());
            }
            IsolateThread slot = WordFactory.pointer(threadHandle);
            int code = CEntryPointActions.enter(slot);
            if (code != 0) {
                NativePool.release(threadHandle);
                CEntryPointActions.failFatally(code, errorMessage.get());
            }
        } else {
            // Legacy mode: no pool initialized, use original enterAttachThread behavior
            int code = CEntryPointActions.enterAttachThread(isolate, true);
            if (code != 0) {
                CEntryPointActions.failFatally(code, errorMessage.get());
            }
        }
    }
}
