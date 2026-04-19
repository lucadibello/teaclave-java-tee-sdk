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

package org.apache.teaclave.javasdk.test.host;

import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.test.common.SM2Service;
import org.apache.teaclave.javasdk.test.common.SM3Service;
import org.apache.teaclave.javasdk.test.common.SM4Service;
import org.apache.teaclave.javasdk.test.common.SMSignAndVerify;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(
        value = 300,
        unit = TimeUnit.SECONDS,
        threadMode = Timeout.ThreadMode.SEPARATE_THREAD
)
public class TestSMEnclave {
    private byte[] sm3Digest(String plaintext) {
        byte[] messages = plaintext.getBytes();
        Digest md = new SM3Digest();
        md.update(messages, 0, messages.length);
        byte[] digest = new byte[md.getDigestSize()];
        md.doFinal(digest, 0);
        return digest;
    }

    @BeforeEach
    final void before() { System.out.println("enter test case: " + this.getClass().getName()); }

    @AfterEach
    final void after() { System.out.println("exit test case: " + this.getClass().getName()); }

    @ParameterizedTest
    @EnumSource(value = EnclaveType.class, mode = EnumSource.Mode.EXCLUDE, names = {"NONE", "EMBEDDED_LIB_OS"})
    void testSM2Enclave(EnclaveType type) throws Exception {
        String plaintext = "Hello World!!!";
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            Iterator<SM2Service> userServices = enclave.load(SM2Service.class);
            assertNotNull(userServices);
            assertTrue(userServices.hasNext());
            SM2Service service = userServices.next();
            String result = service.encryptAndDecryptWithPlaintext(plaintext);
            assertEquals(plaintext, result);
        } finally {
            enclave.destroy();
        }
    }

    @ParameterizedTest
    @EnumSource(value = EnclaveType.class, mode = EnumSource.Mode.EXCLUDE, names = {"NONE", "EMBEDDED_LIB_OS"})
    void testSM3Enclave(EnclaveType type) throws Exception {
        String plaintext = "Hello World!!!";
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            Iterator<SM3Service> userServices = enclave.load(SM3Service.class);
            assertNotNull(userServices);
            assertTrue(userServices.hasNext());
            SM3Service service = userServices.next();
            byte[] result = service.sm3Service(plaintext);
            assertArrayEquals(sm3Digest(plaintext), result);
        } finally {
            enclave.destroy();
        }
    }

    @ParameterizedTest
    @EnumSource(value = EnclaveType.class, mode = EnumSource.Mode.EXCLUDE, names = {"NONE", "EMBEDDED_LIB_OS"})
    void testSM4Enclave(EnclaveType type) throws Exception {
        String plaintext = "Hello World!!!";
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            Iterator<SM4Service> userServices = enclave.load(SM4Service.class);
            assertNotNull(userServices);
            assertTrue(userServices.hasNext());
            SM4Service service = userServices.next();
            assertEquals(service.sm4Service(plaintext), plaintext);
        } finally {
            enclave.destroy();
        }
    }

    @ParameterizedTest
    @EnumSource(value = EnclaveType.class, mode = EnumSource.Mode.EXCLUDE, names = {"NONE", "EMBEDDED_LIB_OS"})
    void testSMSignAndVerify(EnclaveType type) throws Exception {
        String plaintext = "Hello World!!!";
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            Iterator<SMSignAndVerify> userServices = enclave.load(SMSignAndVerify.class);
            assertNotNull(userServices);
            assertTrue(userServices.hasNext());
            SMSignAndVerify service = userServices.next();
            assertTrue(service.smSignAndVerify(plaintext));
        } finally {
            enclave.destroy();
        }
    }
}
