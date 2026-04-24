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

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.test.common.SHAService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(
    value = 30,
    unit = TimeUnit.SECONDS,
    threadMode = Timeout.ThreadMode.SEPARATE_THREAD
)
public class TestEnclaveSHA {

    private String encryptSHA(String plaintext, String SHAType)
        throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(SHAType);
        byte[] messageDigest = md.digest(plaintext.getBytes());
        BigInteger no = new BigInteger(1, messageDigest);
        StringBuilder hashText = new StringBuilder(no.toString(16));
        while (hashText.length() < 32) {
            hashText.insert(0, "0");
        }
        return hashText.toString();
    }

    @BeforeEach
    final void before() {
        System.out.println("enter test case: " + this.getClass().getName());
    }

    @AfterEach
    final void after() {
        System.out.println("exit test case: " + this.getClass().getName());
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void testEnclaveSHA(EnclaveType type) throws Exception {
        String plaintext = "Hello World!!!";
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            Iterator<SHAService> userServices = enclave.load(SHAService.class);
            assertNotNull(userServices);
            assertTrue(userServices.hasNext());
            SHAService service = userServices.next();
            String result = service.encryptPlaintext(plaintext, "SHA-384");
            assertEquals(encryptSHA(plaintext, "SHA-384"), result);
            result = service.encryptPlaintext(plaintext, "SHA-512");
            assertEquals(encryptSHA(plaintext, "SHA-512"), result);
        } finally {
            enclave.destroy();
        }
    }
}
