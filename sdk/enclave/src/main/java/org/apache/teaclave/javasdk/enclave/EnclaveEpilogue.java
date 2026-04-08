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
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.IsolateThread;

/**
 * Custom ECALL epilogue that releases the claimed IsolateThread back to the
 * C-side lock-free pool after leaving the isolate context.
 */
public class EnclaveEpilogue implements CEntryPointOptions.Epilogue {

    @Uninterruptible(reason = "epilogue")
    static void leave() {
        if (IsolateThreadPool.isInitialized()) {
            // Capture the IsolateThread handle before leave() clears it
            IsolateThread current = CurrentIsolate.getCurrentThread();
            long handle = current.rawValue();
            CEntryPointActions.leave();
            NativePool.release(handle);
        } else {
            // Pool not initialized — fall back to default behavior
            CEntryPointActions.leave();
        }
    }
}
