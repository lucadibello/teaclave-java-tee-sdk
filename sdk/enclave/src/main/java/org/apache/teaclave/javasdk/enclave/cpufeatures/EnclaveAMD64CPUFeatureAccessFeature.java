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

package org.apache.teaclave.javasdk.enclave.cpufeatures;

import com.oracle.svm.core.CPUFeatureAccess;
import com.oracle.svm.core.amd64.AMD64CPUFeatureAccess;
import com.oracle.svm.hosted.AMD64CPUFeatureAccessFeature;
import com.oracle.svm.hosted.FeatureHandler;
import com.oracle.svm.hosted.FeatureImpl;
import java.util.EnumSet;
import org.apache.teaclave.javasdk.enclave.EnclaveOptions;
import org.apache.teaclave.javasdk.enclave.EnclavePlatFormSettings;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.hosted.Feature;

/**
 * {@link AMD64CPUFeatureAccessFeature} adds the {@link AMD64CPUFeatureAccess} instance into {@link org.graalvm.nativeimage.ImageSingletons},
 * while {@link AMD64CPUFeatureAccess} eventually calls enclave SDK unsupported CPU feature checking functions. So it will
 * lead to runtime crash in enclave environment.
 * <p>
 * When using --features= flag (instead of @AutomaticFeature), we cannot rely on GraalVM's automatic
 * "most specific class" resolution. Instead, we disable the built-in AMD64CPUFeatureAccessFeature
 * during afterRegistration and register our enclave-safe singleton in beforeAnalysis.
 * <p>
 * The unsupported functions are called by function {@code determineCPUFeatures} in {@code cpuid.c}.
 *
 * @since GraalVM 22.2.0
 */
@Platforms({ Platform.AMD64.class })
public class EnclaveAMD64CPUFeatureAccessFeature
    extends AMD64CPUFeatureAccessFeature {

    /**
     * Disable the built-in AMD64CPUFeatureAccessFeature to prevent it from registering
     * its singleton before we can register ours.
     */
    @Override
    public void afterRegistration(Feature.AfterRegistrationAccess access) {
        if (EnclaveOptions.RunInEnclave.getValue()) {
            FeatureImpl.AfterRegistrationAccessImpl a =
                (FeatureImpl.AfterRegistrationAccessImpl) access;
            FeatureHandler featureHandler = a.getFeatureHandler();
            EnclavePlatFormSettings.disableFeatures(
                featureHandler,
                "com.oracle.svm.hosted.AMD64CPUFeatureAccessFeature"
            );
        }
    }

    /**
     * Override beforeAnalysis to register our enclave-safe CPUFeatureAccess singleton.
     * Since we disabled the built-in feature in afterRegistration, the singleton won't
     * exist yet, and we can register our version.
     */
    @Override
    public void beforeAnalysis(Feature.BeforeAnalysisAccess access) {
        if (ImageSingletons.contains(CPUFeatureAccess.class)) {
            CPUFeatureAccess existing = ImageSingletons.lookup(
                CPUFeatureAccess.class
            );
            if (existing instanceof EnclaveAMD64CPUFeatureAccess) {
                // Already registered our enclave-safe version, nothing to do
                return;
            }
            if (!EnclaveOptions.RunInEnclave.getValue()) {
                // Not in enclave mode, the existing default implementation is fine
                return;
            }
            // This shouldn't happen since we disabled the parent feature,
            // but if it does, we can't replace it without module access
            System.err.println(
                "[WARNING] EnclaveAMD64CPUFeatureAccessFeature: " +
                    "CPUFeatureAccess singleton already registered. Cannot replace."
            );
            return;
        }
        // Singleton not yet registered, let parent handle it (will call our override)
        super.beforeAnalysis(access);
    }

    @Override
    protected AMD64CPUFeatureAccess createCPUFeatureAccessSingleton(
        EnumSet<?> buildtimeCPUFeatures,
        int[] offsets,
        byte[] errorMessageBytes,
        byte[] buildtimeFeatureMaskBytes
    ) {
        if (EnclaveOptions.RunInEnclave.getValue()) {
            return new EnclaveAMD64CPUFeatureAccess(
                buildtimeCPUFeatures,
                offsets,
                errorMessageBytes,
                buildtimeFeatureMaskBytes
            );
        } else {
            return super.createCPUFeatureAccessSingleton(
                buildtimeCPUFeatures,
                offsets,
                errorMessageBytes,
                buildtimeFeatureMaskBytes
            );
        }
    }
}
