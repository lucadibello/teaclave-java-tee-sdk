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

import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.WordPointer;

/**
 * Holder class for CGlobalData instances.
 *
 * In GraalVM 23.0+, CGlobalDataFactory is HOSTED_ONLY. However, static field initializers
 * that call CGlobalDataFactory are processed specially by the native-image builder and
 * converted to constants. This class is initialized at build time (see EnclaveGlobalDataFeature)
 * so the static field initializers run during the build when CGlobalDataFactory is available.
 * The resulting CGlobalData values are constants in the native image.
 */
public final class EnclaveGlobalData {

    public static final CGlobalData<CCharPointer> PROLOGUE_ERROR_MESSAGE =
        CGlobalDataFactory.createCString("Failed to enter (or attach to) the global isolate in the current thread.");

    public static final CGlobalData<WordPointer> CACHED_PAGE_SIZE =
        CGlobalDataFactory.createWord();

    // Private constructor to prevent instantiation
    private EnclaveGlobalData() {
    }
}
