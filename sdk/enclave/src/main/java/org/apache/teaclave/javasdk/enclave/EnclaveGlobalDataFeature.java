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

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/**
 * Feature that ensures EnclaveGlobalData is properly initialized at build time.
 *
 * EnclaveGlobalData contains CGlobalData fields that must be created using CGlobalDataFactory,
 * which is only available during the build process (HOSTED_ONLY in GraalVM 23.0+).
 * By initializing EnclaveGlobalData at build time, the static field initializers can
 * call CGlobalDataFactory methods and the resulting CGlobalData values become constants
 * in the image.
 */
public class EnclaveGlobalDataFeature implements Feature {

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        RuntimeClassInitialization.initializeAtBuildTime("org.apache.teaclave.javasdk.enclave.EnclaveGlobalData");
    }
}
