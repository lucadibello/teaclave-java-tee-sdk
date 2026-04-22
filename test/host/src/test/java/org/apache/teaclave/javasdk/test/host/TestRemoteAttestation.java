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

import org.apache.teaclave.javasdk.host.*;
import org.apache.teaclave.javasdk.host.exception.EnclaveCreatingException;
import org.apache.teaclave.javasdk.host.exception.EnclaveDestroyingException;
import org.apache.teaclave.javasdk.host.exception.RemoteAttestationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(
        value = 300,
        unit = TimeUnit.SECONDS,
        threadMode = Timeout.ThreadMode.SEPARATE_THREAD
)
public class TestRemoteAttestation {

    @BeforeEach
    final void before() { System.out.println("enter test case: " + this.getClass().getName()); }

    @AfterEach
    final void after() { System.out.println("exit test case: " + this.getClass().getName()); }

    @Disabled("Remote attestation requires Intel PCS with a reachable domain + valid PCK cert; "
            + "our domain is no longer registered, so the SGX QE returns 0xe040 "
            + "(p_sgx_get_quote_config). Re-enable once attestation infra is restored.")
    @ParameterizedTest
    @EnumSource(value = EnclaveType.class, names = {"TEE_SDK"})
    void testRemoteAttestation(EnclaveType type) throws Exception {
        Enclave enclave = EnclaveFactory.create(type);
        try {
            assertNotNull(enclave);
            byte[] userData = new byte[64];
            new Random().nextBytes(userData);

            SGXAttestationReport report = (SGXAttestationReport) RemoteAttestation.generateAttestationReport(enclave, userData);
            assertEquals(report.getEnclaveType(), type);
            assertNotNull(report.getQuote());
            assertEquals(0, RemoteAttestation.verifyAttestationReport(report));
            assertNotNull(report.getMeasurementEnclave());
            assertNotNull(report.getMeasurementSigner());
            assertNotNull(report.getUserData());
            assertArrayEquals(userData, report.getUserData());
        } finally {
            enclave.destroy();
        }
    }
}
