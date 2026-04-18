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

package org.apache.teaclave.javasdk.enclave.substitutes;

import org.apache.teaclave.javasdk.enclave.EnclaveOptions;
import org.apache.teaclave.javasdk.enclave.NativeEnclaveRandom;
import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import org.graalvm.nativeimage.UnmanagedMemory;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.nativeimage.c.type.VoidPointer;

import java.io.File;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/**
 * JDK reads random seed from two special files {@code /dev/random} and {@code /dev/urandom} on Linux platform. But they
 * are not necessarily existed in the enclave environment. So we need to substitute the methods that replies on them with
 * a native function that provides the same functionality.
 */
@SuppressWarnings("unused")
public final class NativePRNGSubstitutions {

    static class NoRandomFileInEnclave implements BooleanSupplier {

        @Override
        public boolean getAsBoolean() {
            return EnclaveOptions.RunInEnclave.getValue();
        }
    }

    @TargetClass(className = "sun.security.provider.NativePRNG", innerClass = "Variant", onlyWith = NoRandomFileInEnclave.class)
    static final class Target_sun_security_provider_NativePRNG_Variant {

    }

    @TargetClass(className = "sun.security.provider.NativePRNG", onlyWith = NoRandomFileInEnclave.class)
    static final class Target_sun_security_provider_NativePRNG {
        @Substitute
        private static Target_sun_security_provider_NativePRNG_RandomIO initIO(Target_sun_security_provider_NativePRNG_Variant v) {
            return new Target_sun_security_provider_NativePRNG_RandomIO(new File("/dev/random"), null);
        }
    }

    @TargetClass(className = "sun.security.provider.NativePRNG", innerClass = "RandomIO", onlyWith = NoRandomFileInEnclave.class)
    static final class Target_sun_security_provider_NativePRNG_RandomIO {
        @Alias
        File seedFile;
        @Alias
        byte[] nextBuffer;
        @Alias
        int bufferSize = 256;
        //Checkstyle: stop
        @Alias
        private Object LOCK_GET_BYTES;

        @Alias
        private Object LOCK_GET_SEED;

        @Alias
        private Object LOCK_SET_SEED;
        //Checkstyle: resume

        /**
         * The original {@code sun.security.provider.NativePRNG.RandomIO#RandomIO(File, File)}
         * method initializes field {@code sun.security.provider.NativePRNG.RandomIO#seedIn}
         * and field {@code sun.security.provider.NativePRNG.RandomIO#nextIn} to two special files {@code /dev/random}
         * and {@code /dev/urandom} respectively. However, these two files are not existed in Enclave
         * environment, leading to IOException at native image runtime. So we substitute the original method
         * to avoid creating InputStream from them. <p>
         * The {@code seedIn} and {@code nextIn} fields are only used as input parameter to call
         * {@code sun.security.provider.NativePRNG.RandomIO#readFully(InputStream, byte[])} method
         * to get random seeds. So we substitute it with {@link Target_sun_security_provider_NativePRNG_RandomIO#readFully(InputStream, byte[])}
         * to call the native method that can do the same functionality.
         *
         * @param seedFile /dev/random file, won't get InputStream from it now.
         * @param nextFile /dev/urandom file, will be ignored in the substitution method.
         */
        @Substitute
        @TargetElement(name = TargetElement.CONSTRUCTOR_NAME)
        Target_sun_security_provider_NativePRNG_RandomIO(File seedFile, File nextFile) {
            LOCK_GET_BYTES = new Object();
            LOCK_GET_SEED = new Object();
            LOCK_SET_SEED = new Object();
            this.seedFile = seedFile;
            nextBuffer = new byte[bufferSize];
        }

        /*
         * Fetch entropy directly from sgx_read_rand (RDRAND) inside the enclave.
         *
         * The upstream implementation routed through callbacks_t.get_random_number,
         * a host-shaped function pointer reached via EnclaveEntry.getCallBackMethods().
         * Under concurrent ECALLs that path is hostile to GraalVM's safepoint model:
         * it requires a per-TCS callbacks lookup on every entropy fetch and historically
         * exposed the calling thread to a safepoint window (see MULTITHREADING.md section 1.2).
         * NativeEnclaveRandom.readRand calls sgx_read_rand entirely inside the enclave -
         * no OCALL, no callbacks indirection, no safepoint hazard.
         */
        @Substitute
        private static void readFully(InputStream in, byte[] data) {
            int len = data.length;
            CCharPointer bytes = UnmanagedMemory.malloc(len);
            try {
                int ret = NativeEnclaveRandom.readRand((VoidPointer) bytes, len);
                if (ret != 0) {
                    throw new RuntimeException("sgx_read_rand failed inside enclave. sgx_status_t=" + ret);
                }
                CTypeConversion.asByteBuffer(bytes, len).get(data);
            } finally {
                UnmanagedMemory.free(bytes);
            }
        }
    }
}
