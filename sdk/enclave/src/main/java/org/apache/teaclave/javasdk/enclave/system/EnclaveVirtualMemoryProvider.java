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

package org.apache.teaclave.javasdk.enclave.system;

import org.apache.teaclave.javasdk.enclave.EnclaveGlobalData;
import org.apache.teaclave.javasdk.enclave.c.EnclaveEnvironment;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.posix.PosixVirtualMemoryProvider;
import com.oracle.svm.core.util.VMError;
import org.graalvm.compiler.word.Word;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

/**
 * Virtual memory implementation for Enclave environment. The {@code sysconf(_SC_PAGE_SIZE())} might be
 * invalid in TEE and OE SDK environment, so we read it from a custom native method.
 *
 * Note: The cached page size CGlobalData is created at build time by EnclaveGlobalDataFeature
 * and accessed via EnclaveGlobalData.singleton() to avoid referencing CGlobalDataFactory
 * which is HOSTED_ONLY in GraalVM 23.0+.
 */
public class EnclaveVirtualMemoryProvider extends PosixVirtualMemoryProvider {

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static UnsignedWord getVPageSize() {
        Word value = EnclaveGlobalData.CACHED_PAGE_SIZE.get().read();
        if (value.equal(WordFactory.zero())) {
            long queried = EnclaveEnvironment.getVirtualPageSize();
            if (queried == -1L) {
                throw VMError.shouldNotReachHere("Virtual memory page size (_SC_PAGE_SIZE) not available");
            }
            value = WordFactory.unsigned(queried);
            EnclaveGlobalData.CACHED_PAGE_SIZE.get().write(value);
        }
        return value;
    }

    @Override
    @Uninterruptible(reason = "May be called from uninterruptible code.", mayBeInlined = true)
    public UnsignedWord getGranularity() {
        return getVPageSize();
    }
}
