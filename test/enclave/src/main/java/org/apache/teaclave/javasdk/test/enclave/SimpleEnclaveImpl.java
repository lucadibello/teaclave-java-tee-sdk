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

package org.apache.teaclave.javasdk.test.enclave;

import org.apache.teaclave.javasdk.test.common.SimpleService;
import com.google.auto.service.AutoService;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@AutoService(SimpleService.class)
public class SimpleEnclaveImpl implements SimpleService {

    private Thread asyncThread;

    // ---- Shared state mimicking StreamingDPMechanism ----
    private final Object bufferLock = new Object();
    private Map<String, Double> stagingCounts = new HashMap<>();
    private Map<String, Set<String>> stagingUsers = new HashMap<>();
    private volatile Map<String, Long> dpSnapshotResult;
    private volatile Throwable dpSnapshotError;
    
    @Override
    public boolean startAsyncThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            long end = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < end) {
                // spin
            }
        });
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public void stopAsyncThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            asyncThread.interrupt();
        }
    }

    @Override
    public boolean isAsyncThreadRunning() {
        return asyncThread != null && asyncThread.isAlive();
    }

    @Override
    public int someWork() {
        return fib(30);
    }

    private int fib(int n) {
        if (n <= 1) return n;
        return fib(n - 1) + fib(n - 2);
    }

    @Override
    public boolean startHeapAllocatingThread() {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            long iterations = 0;
            while (!Thread.currentThread().isInterrupted()) {
                HashMap<String, HashSet<String>> forest = new HashMap<>();
                for (int k = 0; k < 2000; k++) {
                    HashSet<String> users = new HashSet<>();
                    for (int u = 0; u < 500; u++) {
                        users.add("user-" + u + "-key-" + k + "-iter-" + iterations);
                    }
                    forest.put("key-" + k, users);
                }
                LinkedHashMap<String, Long> result = new LinkedHashMap<>();
                for (Map.Entry<String, HashSet<String>> e : forest.entrySet()) {
                    result.put(e.getKey(), (long) e.getValue().size());
                }
                ArrayList<Map<String, Long>> history = new ArrayList<>();
                for (int h = 0; h < 50; h++) {
                    history.add(new LinkedHashMap<>(result));
                }
                iterations++;
            }
        });
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> computeResult(int entries) {
        LinkedHashMap<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < entries; i++) {
            String key = "result-key-" + i + "-suffix-" + (i * 31);
            HashSet<String> scratch = new HashSet<>();
            for (int j = 0; j < 100; j++) {
                scratch.add("scratch-" + i + "-" + j);
            }
            result.put(key, (long) scratch.size() + fib(15 + (i % 8)));
        }
        return result;
    }

    @Override
    public void addContribution(String userId, String word, double count) {
        synchronized (bufferLock) {
            stagingCounts.merge(word, count, Double::sum);
            stagingUsers.computeIfAbsent(word, k -> new HashSet<>()).add(userId);
        }
    }

    @Override
    public boolean startDPSnapshotThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        dpSnapshotResult = null;
        dpSnapshotError = null;

        asyncThread = new Thread(() -> {
            try {
                dpSnapshotResult = runDPSnapshot(numKeys, maxTimeSteps);
            } catch (Throwable t) {
                dpSnapshotError = t;
            }
        }, "enclave-dp-snapshot");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> pollDPSnapshotResult() {
        if (dpSnapshotError != null) {
            throw new RuntimeException("DP snapshot failed: " + dpSnapshotError.getMessage(), dpSnapshotError);
        }
        return dpSnapshotResult;
    }

    private Map<String, Long> runDPSnapshot(int numKeys, int maxTimeSteps) {
        final Map<String, Double> drainedCounts;
        final Map<String, Set<String>> drainedUsers;
        synchronized (bufferLock) {
            drainedCounts = this.stagingCounts;
            drainedUsers = this.stagingUsers;
            this.stagingCounts = new HashMap<>();
            this.stagingUsers = new HashMap<>();
        }

        SecureRandom rng = new SecureRandom();
        int height = (int) Math.ceil(Math.log(maxTimeSteps) / Math.log(2));
        int numLeaves = 1 << height;
        int treeSize = 2 * numLeaves - 1;

        Map<String, double[]> forest = new HashMap<>();
        Set<String> allKeys = new HashSet<>(drainedCounts.keySet());
        for (int k = 0; k < numKeys; k++) {
            allKeys.add("synth-key-" + k);
        }

        for (String key : allKeys) {
            double sigma = 1.5 + rng.nextDouble();
            double[] tree = new double[treeSize];
            for (int i = 0; i < treeSize; i++) {
                tree[i] = rng.nextGaussian() * sigma;
            }
            forest.put(key, tree);

            double count = drainedCounts.getOrDefault(key, 0.0);
            for (int t = 0; t < Math.min(maxTimeSteps, numLeaves); t++) {
                int idx = numLeaves - 1 + t;
                double val = count + rng.nextGaussian() * 0.1;
                while (idx > 0) {
                    tree[idx] += val;
                    idx = (idx - 1) / 2;
                }
                tree[0] += val;
            }
        }

        Map<String, Double> currentSums = new HashMap<>();
        int[] currentLevel = new int[numLeaves];
        int[] nextLevel = new int[numLeaves];

        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            int timeStep = Math.min(maxTimeSteps - 1, numLeaves - 1);
            int indexBinary = timeStep + 1;
            int nodeIndex = 0;
            double sPriv = 0.0;

            for (int j = 0; j <= height; j++) {
                int levelBit = (indexBinary >> (height - j)) & 1;
                if (levelBit == 1) {
                    int leftSibling;
                    if (nodeIndex == 0) leftSibling = 0;
                    else if (nodeIndex % 2 == 0) leftSibling = nodeIndex - 1;
                    else leftSibling = nodeIndex;

                    int kappa = height - j + 1;
                    double estimate = 0.0;
                    int currentSize = 0;
                    currentLevel[currentSize++] = leftSibling;

                    for (int lev = 0; lev < kappa; lev++) {
                        double sumLev = 0;
                        int nextSize = 0;
                        for (int ci = 0; ci < currentSize; ci++) {
                            int idx = currentLevel[ci];
                            if (idx < tree.length) {
                                sumLev += tree[idx];
                                if (lev < kappa - 1) {
                                    nextLevel[nextSize++] = 2 * idx + 1;
                                    nextLevel[nextSize++] = 2 * idx + 2;
                                }
                            }
                        }
                        double c_j = (1.0 / (1L << lev)) / (2.0 * (1.0 - 1.0 / (1L << kappa)));
                        estimate += c_j * sumLev;
                        System.arraycopy(nextLevel, 0, currentLevel, 0, nextSize);
                        currentSize = nextSize;
                    }
                    sPriv += estimate;
                }
                if (j < height) {
                    int pathBit = (timeStep >> (height - 1 - j)) & 1;
                    nodeIndex = (pathBit == 0) ? 2 * nodeIndex + 1 : 2 * nodeIndex + 2;
                }
            }
            currentSums.put(entry.getKey(), sPriv);
        }

        double mu = 5.0;
        double probit = 4.2649;

        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            double sigma = 1.5;
            for (int tr_p = 1; tr_p < maxTimeSteps; tr_p++) {
                int ib = tr_p + 1;
                int ni = 0;
                double noisyCount = 0.0;
                for (int j = 0; j <= height; j++) {
                    int lb = (ib >> (height - j)) & 1;
                    if (lb == 1) {
                        int ls = (ni == 0) ? 0 : (ni % 2 == 0 ? ni - 1 : ni);
                        if (ls < tree.length) {
                            noisyCount += tree[ls];
                        }
                    }
                    if (j < height) {
                        int pb = (tr_p >> (height - 1 - j)) & 1;
                        ni = (pb == 0) ? 2 * ni + 1 : 2 * ni + 2;
                    }
                }
                double variance = (sigma * sigma) / (2.0 * (1.0 - 1.0 / (1L << height)));
                double tau = Math.sqrt(variance) * probit;
                if (noisyCount >= mu + tau) {
                    break;
                }
            }
        }

        LinkedHashMap<String, Long> histogram = new LinkedHashMap<>();
        currentSums.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> histogram.put(e.getKey(), Math.max(0L, Math.round(e.getValue()))));
        return histogram;
    }

    @Override
    public boolean startSecureRandomThread(int arraySize) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            SecureRandom rng = new SecureRandom();
            double[] buf = new double[arraySize];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = rng.nextGaussian();
            }
            double sum = 0;
            for (double v : buf) sum += v;
        }, "enclave-secure-random");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public void noop() {}

    @Override
    public boolean startPlainRandomThread(int arraySize) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            Random rng = new Random(42);
            double[] buf = new double[arraySize];
            for (int i = 0; i < buf.length; i++) {
                buf[i] = rng.nextGaussian();
            }
            double sum = 0;
            for (double v : buf) sum += v;
        }, "enclave-plain-random");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public boolean startCryptoThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            try {
                SecretKey key = KeyGenerator.getInstance("ChaCha20").generateKey();
                SecureRandom rng = new SecureRandom();
                byte[] plaintext = new byte[1024];
                rng.nextBytes(plaintext);
                for (int i = 0; i < iterations; i++) {
                    byte[] nonce = new byte[12];
                    rng.nextBytes(nonce);
                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "enclave-crypto");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public boolean startCipherCounterNonceThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        asyncThread = new Thread(() -> {
            try {
                byte[] keyBytes = new byte[32];
                new Random(42).nextBytes(keyBytes);
                SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");
                byte[] plaintext = new byte[1024];
                new Random(99).nextBytes(plaintext);
                byte[] nonce = new byte[12];
                for (int i = 0; i < iterations; i++) {
                    nonce[8] = (byte)(i >> 24);
                    nonce[9] = (byte)(i >> 16);
                    nonce[10] = (byte)(i >> 8);
                    nonce[11] = (byte) i;
                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "enclave-cipher-counter");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    private volatile Map<String, Long> fullSnapshotNoSecureRandomResult;
    private volatile Throwable fullSnapshotNoSecureRandomError;

    @Override
    public boolean startFullSnapshotNoSecureRandomThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) {
            return false;
        }
        fullSnapshotNoSecureRandomResult = null;
        fullSnapshotNoSecureRandomError = null;
        asyncThread = new Thread(() -> {
            try {
                fullSnapshotNoSecureRandomResult = runDPSnapshotPureJava(numKeys, maxTimeSteps);
            } catch (Throwable t) {
                fullSnapshotNoSecureRandomError = t;
            }
        }, "enclave-snap-pure-java");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public Map<String, Long> pollFullSnapshotNoSecureRandomResult() {
        if (fullSnapshotNoSecureRandomError != null) {
            throw new RuntimeException("Full snapshot (no SecureRandom) failed", fullSnapshotNoSecureRandomError);
        }
        return fullSnapshotNoSecureRandomResult;
    }

    private Map<String, Long> runDPSnapshotPureJava(int numKeys, int maxTimeSteps) {
        final Map<String, Double> drainedCounts;
        final Map<String, Set<String>> drainedUsers;
        synchronized (bufferLock) {
            drainedCounts = this.stagingCounts;
            drainedUsers = this.stagingUsers;
            this.stagingCounts = new HashMap<>();
            this.stagingUsers = new HashMap<>();
        }
        Random rng = new Random();
        int height = (int) Math.ceil(Math.log(maxTimeSteps) / Math.log(2));
        int numLeaves = 1 << height;
        int treeSize = 2 * numLeaves - 1;
        Map<String, double[]> forest = new HashMap<>();
        Set<String> allKeys = new HashSet<>(drainedCounts.keySet());
        for (int k = 0; k < numKeys; k++) allKeys.add("synth-key-" + k);
        for (String key : allKeys) {
            double sigma = 1.5 + rng.nextDouble();
            double[] tree = new double[treeSize];
            for (int i = 0; i < treeSize; i++) tree[i] = rng.nextGaussian() * sigma;
            forest.put(key, tree);
            double count = drainedCounts.getOrDefault(key, 0.0);
            for (int t = 0; t < Math.min(maxTimeSteps, numLeaves); t++) {
                int idx = numLeaves - 1 + t;
                double val = count + rng.nextGaussian() * 0.1;
                while (idx > 0) { tree[idx] += val; idx = (idx - 1) / 2; }
                tree[0] += val;
            }
        }
        Map<String, Double> currentSums = new HashMap<>();
        int[] currentLevel = new int[numLeaves];
        int[] nextLevel = new int[numLeaves];
        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            int timeStep = Math.min(maxTimeSteps - 1, numLeaves - 1);
            int indexBinary = timeStep + 1;
            int nodeIndex = 0;
            double sPriv = 0.0;
            for (int j = 0; j <= height; j++) {
                int levelBit = (indexBinary >> (height - j)) & 1;
                if (levelBit == 1) {
                    int leftSibling = (nodeIndex == 0) ? 0 : (nodeIndex % 2 == 0 ? nodeIndex - 1 : nodeIndex);
                    int kappa = height - j + 1;
                    double estimate = 0.0;
                    int currentSize = 0;
                    currentLevel[currentSize++] = leftSibling;
                    for (int lev = 0; lev < kappa; lev++) {
                        double sumLev = 0;
                        int nextSize = 0;
                        for (int ci = 0; ci < currentSize; ci++) {
                            int idx = currentLevel[ci];
                            if (idx < tree.length) {
                                sumLev += tree[idx];
                                if (lev < kappa - 1) {
                                    nextLevel[nextSize++] = 2 * idx + 1;
                                    nextLevel[nextSize++] = 2 * idx + 2;
                                }
                            }
                        }
                        double c_j = (1.0 / (1L << lev)) / (2.0 * (1.0 - 1.0 / (1L << kappa)));
                        estimate += c_j * sumLev;
                        System.arraycopy(nextLevel, 0, currentLevel, 0, nextSize);
                        currentSize = nextSize;
                    }
                    sPriv += estimate;
                }
                if (j < height) {
                    int pathBit = (timeStep >> (height - 1 - j)) & 1;
                    nodeIndex = (pathBit == 0) ? 2 * nodeIndex + 1 : 2 * nodeIndex + 2;
                }
            }
            currentSums.put(entry.getKey(), sPriv);
        }
        double mu = 5.0;
        double probit = 4.2649;
        for (Map.Entry<String, double[]> entry : forest.entrySet()) {
            double[] tree = entry.getValue();
            double sigma = 1.5;
            for (int tr_p = 1; tr_p < maxTimeSteps; tr_p++) {
                int ib = tr_p + 1;
                int ni = 0;
                double noisyCount = 0.0;
                for (int j = 0; j <= height; j++) {
                    int lb = (ib >> (height - j)) & 1;
                    if (lb == 1) {
                        int ls = (ni == 0) ? 0 : (ni % 2 == 0 ? ni - 1 : ni);
                        if (ls < tree.length) noisyCount += tree[ls];
                    }
                    if (j < height) {
                        int pb = (tr_p >> (height - 1 - j)) & 1;
                        ni = (pb == 0) ? 2 * ni + 1 : 2 * ni + 2;
                    }
                }
                double variance = (sigma * sigma) / (2.0 * (1.0 - 1.0 / (1L << height)));
                double tau = Math.sqrt(variance) * probit;
                if (noisyCount >= mu + tau) break;
            }
        }
        LinkedHashMap<String, Long> histogram = new LinkedHashMap<>();
        currentSums.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> histogram.put(e.getKey(), Math.max(0L, Math.round(e.getValue()))));
        return histogram;
    }

    private volatile Map<String, Long> optionAPlaintextResult;
    private volatile Throwable optionAError;

    @Override
    public boolean startOptionASnapshotThread(int numKeys, int maxTimeSteps) {
        if (asyncThread != null && asyncThread.isAlive()) return false;
        optionAPlaintextResult = null;
        optionAError = null;
        asyncThread = new Thread(() -> {
            try {
                optionAPlaintextResult = runDPSnapshotPureJava(numKeys, maxTimeSteps);
            } catch (Throwable t) {
                optionAError = t;
            }
        }, "enclave-option-a-snap");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public int pollOptionAResult() {
        if (optionAError != null) throw new RuntimeException("Option A snapshot failed", optionAError);
        Map<String, Long> plaintext = optionAPlaintextResult;
        if (plaintext == null) return -1;
        optionAPlaintextResult = null;
        try {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Long> e : plaintext.entrySet()) {
                if (!first) sb.append(',');
                sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
                first = false;
            }
            sb.append('}');
            byte[] payloadBytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] keyBytes = new byte[32];
            new Random(42).nextBytes(keyBytes);
            SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");
            byte[] nonce = new byte[12];
            Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
            byte[] ciphertext = cipher.doFinal(payloadBytes);
            return ciphertext.length;
        } catch (Exception e) {
            throw new RuntimeException("Option A poll encryption failed", e);
        }
    }

    @Override
    public boolean startCryptoWithPlainRandomThread(int iterations) {
        if (asyncThread != null && asyncThread.isAlive()) return false;
        asyncThread = new Thread(() -> {
            try {
                byte[] keyBytes = new byte[32];
                new Random(42).nextBytes(keyBytes);
                SecretKey key = new javax.crypto.spec.SecretKeySpec(keyBytes, "ChaCha20");
                Random rng = new Random(42);
                byte[] plaintext = new byte[1024];
                rng.nextBytes(plaintext);
                for (int i = 0; i < iterations; i++) {
                    byte[] nonce = new byte[12];
                    rng.nextBytes(nonce);
                    Cipher cipher = Cipher.getInstance("ChaCha20-Poly1305");
                    cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(nonce));
                    byte[] ciphertext = cipher.doFinal(plaintext);
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(nonce));
                    cipher.doFinal(ciphertext);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "enclave-crypto-plain");
        asyncThread.setDaemon(true);
        asyncThread.start();
        return true;
    }

    @Override
    public int runInlineThread(int fibN) {
        int[] result = {-1};
        Thread t = new Thread(() -> {
            result[0] = fib(fibN);
        }, "enclave-inline-fib");
        t.start();
        return result[0];
    }

    @Override
    public boolean isInlineThreadSynchronous() {
        boolean[] flag = {false};
        Thread t = new Thread(() -> {
            flag[0] = true;
        }, "enclave-inline-flag");
        t.start();
        return flag[0];
    }

    @Override
    public long runNInlineThreads(int n, int fibN) {
        long total = 0;
        for (int i = 0; i < n; i++) {
            long[] partial = {0};
            Thread t = new Thread(() -> {
                partial[0] = fib(fibN);
            }, "enclave-inline-" + i);
            t.start();
            total += partial[0];
        }
        return total;
    }

    @Override
    public int echoInt(int value) {
        return value;
    }

    @Override
    public int echoIntWithWork(int value, int cpuLoops) {
        int acc = 0;
        for (int i = 0; i < cpuLoops; i++) {
            acc = (acc * 1103515245 + 12345) & 0x7fffffff;
        }
        return value | (acc & 0);
    }
}
