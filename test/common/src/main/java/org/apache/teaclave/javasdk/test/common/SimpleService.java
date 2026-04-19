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

package org.apache.teaclave.javasdk.test.common;

import org.apache.teaclave.javasdk.common.annotations.EnclaveService;
import java.util.Map;

@EnclaveService
public interface SimpleService {
    boolean startAsyncThread();
    void stopAsyncThread();
    boolean isAsyncThreadRunning();
    int someWork();

    /**
     * Starts a background thread that continuously allocates heap objects (HashMaps, HashSets,
     * ArrayLists) to trigger GC pressure inside the enclave isolate.
     */
    boolean startHeapAllocatingThread();

    /**
     * Performs heap-allocating work (builds a Map with String keys and Long values) and returns
     * it.
     *
     * @param entries number of map entries to produce
     * @return a map of string keys to long values
     */
    Map<String, Long> computeResult(int entries);

    // ---- Reproduce exact production crash pattern ----

    /**
     * Adds a contribution to shared staging buffers.
     *
     * @param userId user identifier
     * @param word   aggregation key
     * @param count  clamped contribution value
     */
    void addContribution(String userId, String word, double count);

    /**
     * Starts a background thread inside the enclave that mimics the production
     * StreamingDPMechanism.snapshot() workload.
     *
     * @param numKeys      number of keys to simulate
     * @param maxTimeSteps maximum time steps for prediction (controls CPU intensity)
     * @return true if the thread was started, false if one is already running
     */
    boolean startDPSnapshotThread(int numKeys, int maxTimeSteps);

    /**
     * Returns the snapshot result computed by the background thread.
     */
    Map<String, Long> pollDPSnapshotResult();

    // ---- Minimal crash reproduction ----

    /**
     * Starts a background thread that allocates Gaussian noise via SecureRandom.
     *
     * @param arraySize number of doubles to fill with Gaussian noise
     * @return true if started, false if already running
     */
    boolean startSecureRandomThread(int arraySize);

    /**
     * No-op ECALL.
     */
    void noop();

    // ---- Control test with java.util.Random ----

    /**
     * Same as startSecureRandomThread(int) but uses java.util.Random instead of SecureRandom.
     *
     * @param arraySize number of doubles to fill with Gaussian noise
     * @return true if started, false if already running
     */
    boolean startPlainRandomThread(int arraySize);

    // ---- Crypto isolation ----

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption in a loop.
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCryptoThread(int iterations);

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption using
     * java.util.Random for nonce generation (no SecureRandom).
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCryptoWithPlainRandomThread(int iterations);

    // ---- Cipher with counter-based nonces (no SecureRandom) ----

    /**
     * Starts a background thread that performs ChaCha20-Poly1305 encryption using a
     * monotonic counter encoded into the nonce bytes.
     *
     * @param iterations number of encrypt/decrypt cycles
     * @return true if started, false if already running
     */
    boolean startCipherCounterNonceThread(int iterations);

    // ---- Long-running pure-Java snapshot, concurrent addContribution() ----

    /**
     * Starts a background thread that replicates the full StreamingDPMechanism.snapshot()
     * workload using only java.util.Random (no SecureRandom).
     *
     * @param numKeys      number of keys to simulate (controls allocation volume)
     * @param maxTimeSteps maximum time steps for prediction (controls CPU intensity / duration)
     * @return true if the thread was started, false if one is already running
     */
    boolean startFullSnapshotNoSecureRandomThread(int numKeys, int maxTimeSteps);

    /**
     * Returns the result from the thread started by startFullSnapshotNoSecureRandomThread.
     */
    Map<String, Long> pollFullSnapshotNoSecureRandomResult();

    // ---- Option A validation — snapshot on bg thread, encrypt on poll ECALL ----

    /**
     * Starts a background thread that computes only the plaintext snapshot.
     *
     * @param numKeys      number of keys to simulate
     * @param maxTimeSteps maximum time steps for prediction
     * @return true if started, false if already running
     */
    boolean startOptionASnapshotThread(int numKeys, int maxTimeSteps);

    /**
     * Polls the result of the Option A snapshot.
     */
    int pollOptionAResult();

    // ---- Enclave-side Thread.start() with synchronous pthread_create shim ----

    /**
     * Spawns a thread inside the enclave via new Thread().start() and returns the
     * result computed by that thread.
     *
     * @param fibN the Fibonacci index to compute inside the spawned thread
     * @return the Fibonacci result produced by the enclave-side thread
     */
    int runInlineThread(int fibN);

    /**
     * Spawns a thread inside the enclave that sets a shared flag.
     *
     * @return true if the enclave-side thread set the flag before the ECALL returned
     */
    boolean isInlineThreadSynchronous();

    /**
     * Spawns N threads sequentially inside the enclave.
     *
     * @param n   number of threads to start sequentially
     * @param fibN Fibonacci index each thread computes
     * @return sum of all Fibonacci results
     */
    long runNInlineThreads(int n, int fibN);

    // ---- Host-side multithreading stress tests ----

    /**
     * Returns the input value unchanged.
     */
    int echoInt(int value);

    /**
     * Returns the input value after spinning for roughly cpuLoops.
     */
    int echoIntWithWork(int value, int cpuLoops);
}
