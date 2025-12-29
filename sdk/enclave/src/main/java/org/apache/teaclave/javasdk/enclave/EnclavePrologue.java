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

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import org.graalvm.nativeimage.Isolate;

/**
 * Prologue for enclave entry points that attaches the current thread to the isolate.
 *
 * Note: The error message CGlobalData is created at build time by EnclaveGlobalDataFeature
 * and accessed via EnclaveGlobalData.singleton() to avoid referencing CGlobalDataFactory
 * which is HOSTED_ONLY in GraalVM 23.0+.
 */
public class EnclavePrologue implements CEntryPointOptions.Prologue {

    @Uninterruptible(reason = "prologue")
    static void enter(Isolate isolate) {
        int code = CEntryPointActions.enterAttachThread(isolate, true);
        if (code != 0) {
            CEntryPointActions.failFatally(code, EnclaveGlobalData.PROLOGUE_ERROR_MESSAGE.get());
        }
    }
}
