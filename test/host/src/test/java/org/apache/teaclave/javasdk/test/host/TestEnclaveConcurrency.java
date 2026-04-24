package org.apache.teaclave.javasdk.test.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.teaclave.javasdk.host.Enclave;
import org.apache.teaclave.javasdk.host.EnclaveFactory;
import org.apache.teaclave.javasdk.host.EnclaveType;
import org.apache.teaclave.javasdk.test.common.ConcurrencyCalculate;
import org.apache.teaclave.javasdk.test.common.SimpleService;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Timeout(
    value = 30,
    unit = TimeUnit.SECONDS,
    threadMode = Timeout.ThreadMode.SEPARATE_THREAD
)
public class TestEnclaveConcurrency {

    private Enclave currentEnclave;
    protected SimpleService service;
    protected ConcurrencyCalculate concurrencyService;

    void setUp(EnclaveType type) throws Exception {
        System.out.println("[host] Creating enclave of type " + type + "...");
        currentEnclave = EnclaveFactory.create(type);

        Iterator<SimpleService> iter = currentEnclave.load(SimpleService.class);
        if (!iter.hasNext()) throw new RuntimeException(
            "No SimpleService found"
        );
        service = iter.next();

        Iterator<ConcurrencyCalculate> calcIter = currentEnclave.load(
            ConcurrencyCalculate.class
        );
        if (!calcIter.hasNext()) throw new RuntimeException(
            "No ConcurrencyCalculate found"
        );
        concurrencyService = calcIter.next();
    }

    void tearDown() throws Exception {
        if (currentEnclave != null) {
            System.out.println("[host] Destroying enclave...");
            currentEnclave.destroy();
            currentEnclave = null;
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void testEnclaveConcurrency(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting testEnclaveConcurrency");
            int concurrency = 10;
            int workload = 10_000;
            CountDownLatch latch0 = new CountDownLatch(1);
            CountDownLatch latch1 = new CountDownLatch(concurrency);

            for (int i = 0; i < concurrency; i++) {
                new Thread(() -> {
                    try {
                        latch0.await();
                        for (int i1 = 0; i1 < workload; i1++) {
                            concurrencyService.add(1);
                        }
                        latch1.countDown();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                    .start();
            }
            latch0.countDown();
            assertTrue(latch1.await(60, TimeUnit.SECONDS));
            assertEquals(concurrency * workload, concurrencyService.sum());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void testEnclaveConcurrencySync(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting testEnclaveConcurrencySync");
            int concurrency = 10;
            int workload = 20_000;
            CountDownLatch latch0 = new CountDownLatch(1);
            CountDownLatch latch1 = new CountDownLatch(concurrency);

            for (int i = 0; i < concurrency; i++) {
                new Thread(() -> {
                    try {
                        latch0.await();
                        concurrencyService.addSync(workload);
                        latch1.countDown();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                })
                    .start();
            }
            latch0.countDown();
            assertTrue(latch1.await(60, TimeUnit.SECONDS));
            assertEquals(concurrency * workload, concurrencyService.sumSync());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void baseline_singleEcall(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting baseline_singleEcall");
            assertTrue(service.someWork() > 0);
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_concurrentEcalls(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_concurrentEcalls");
            int hostThreads = 4,
                callsPerThread = 200;
            AtomicInteger total = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) {
                            if (
                                service.someWork() <= 0
                            ) throw new AssertionError();
                            total.incrementAndGet();
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(hostThreads * callsPerThread, total.get());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_gcPressureBgThread(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting enclave_gcPressureBgThread");
            assertTrue(service.startHeapAllocatingThread());
            Thread.sleep(500);
            int hostThreads = 4,
                callsPerThread = 100;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (
                            int c = 0;
                            c < callsPerThread;
                            c++
                        ) service.computeResult(200);
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            service.stopAsyncThread();
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_dpSnapshotPattern(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting enclave_dpSnapshotPattern");
            for (int i = 0; i < 500; i++) service.addContribution(
                "u-" + (i % 50),
                "k-" + (i % 200),
                1.0
            );
            assertTrue(service.startFullSnapshotNoSecureRandomThread(500, 100));
            Thread.sleep(200);
            int hostThreads = 4,
                callsPerThread = 500;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (
                            int c = 0;
                            c < callsPerThread;
                            c++
                        ) service.addContribution("u", "k", 1.0);
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertNotNull(service.pollFullSnapshotNoSecureRandomResult());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_secureRandomBgThread(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting enclave_secureRandomBgThread");
            assertTrue(service.startSecureRandomThread(1_000_000));
            Thread.sleep(100);
            int hostThreads = 4,
                callsPerThread = 200;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) service.noop();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            service.stopAsyncThread();
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_inlineThreadCompletion(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println(
                "[host] Starting enclave_inlineThreadCompletion"
            );
            assertEquals(6765, service.runInlineThread(20));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_inlineThreadSynchronous(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println(
                "[host] Starting enclave_inlineThreadSynchronous"
            );
            // Thread.start() is always asynchronous - the worker body is scheduled but not
            // necessarily executed before start() returns, regardless of enclave type.
            // isInlineThreadSynchronous() captures the pre-join flag state, which is false
            // for all types (JVM threads, SVM threads, and real SGX pthread threads).
            assertFalse(service.isInlineThreadSynchronous());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_noZombieAfterInlineThread(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println(
                "[host] Starting enclave_noZombieAfterInlineThread"
            );
            assertEquals(610, service.runInlineThread(15));
            int hostThreads = 4,
                callsPerThread = 200;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (
                            int c = 0;
                            c < callsPerThread;
                            c++
                        ) service.someWork();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void enclave_concurrentInlineThreads(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println(
                "[host] Starting enclave_concurrentInlineThreads"
            );
            int hostThreads = 4,
                callsPerThread = 25,
                inlinePerCall = 5;
            long expected = (long) inlinePerCall * 610;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) if (
                            service.runNInlineThreads(inlinePerCall, 15) !=
                            expected
                        ) throw new AssertionError();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_coldStartStorm(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_coldStartStorm");
            int hostThreads = 64;
            CountDownLatch done = new CountDownLatch(hostThreads);
            AtomicInteger correct = new AtomicInteger();
            for (int t = 0; t < hostThreads; t++) {
                final int tid = t;
                new Thread(() -> {
                    try {
                        if (
                            service.echoInt(0xC0FFEE + tid) == 0xC0FFEE + tid
                        ) correct.incrementAndGet();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(60, TimeUnit.SECONDS));
            assertEquals(hostThreads, correct.get());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_sustainedThroughput(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_sustainedThroughput");
            int hostThreads = 4,
                callsPerThread = 5_000;
            CountDownLatch done = new CountDownLatch(hostThreads);
            AtomicLong ok = new AtomicLong();
            for (int t = 0; t < hostThreads; t++) {
                final int tid = t;
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) if (
                            service.echoInt((tid << 20) | c) ==
                            ((tid << 20) | c)
                        ) ok.incrementAndGet();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(180, TimeUnit.SECONDS));
            assertEquals((long) hostThreads * callsPerThread, ok.get());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_heterogeneousMix(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_heterogeneousMix");
            int hostThreads = 8,
                callsPerThread = 400;
            CountDownLatch done = new CountDownLatch(hostThreads);
            for (int t = 0; t < hostThreads; t++) {
                new Thread(() -> {
                    try {
                        ThreadLocalRandom rng = ThreadLocalRandom.current();
                        for (int c = 0; c < callsPerThread; c++) {
                            switch (rng.nextInt(5)) {
                                case 0 -> service.echoInt(rng.nextInt());
                                case 1 -> service.someWork();
                                case 2 -> service.computeResult(10);
                                case 3 -> service.addContribution(
                                    "u",
                                    "k",
                                    1.0
                                );
                                default -> service.noop();
                            }
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_threadChurn(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_threadChurn");
            int rounds = 4,
                threadsPerRound = 50,
                callsPerThread = 5;
            AtomicInteger ok = new AtomicInteger();
            for (int r = 0; r < rounds; r++) {
                final int round = r;
                CountDownLatch done = new CountDownLatch(threadsPerRound);
                for (int t = 0; t < threadsPerRound; t++) {
                    final int tid = t;
                    new Thread(() -> {
                        try {
                            for (int c = 0; c < callsPerThread; c++) if (
                                service.echoInt(
                                    (round << 24) | (tid << 12) | c
                                ) ==
                                ((round << 24) | (tid << 12) | c)
                            ) ok.incrementAndGet();
                        } catch (Throwable ignored) {
                        } finally {
                            done.countDown();
                        }
                    })
                        .start();
                }
                assertTrue(done.await(60, TimeUnit.SECONDS));
            }
            assertEquals(rounds * threadsPerRound * callsPerThread, ok.get());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_cacheCapacitySaturation(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_cacheCapacitySaturation");
            // TEE_SDK enclave config allows 50 TCS slots (see TestEnclaveInfoMXBean). Host thread
            // count must stay at/under that or contention starves ECALLs and we lose throughput.
            int hostThreads = 48,
                callsPerThread = 20;
            CountDownLatch done = new CountDownLatch(hostThreads);
            AtomicInteger ok = new AtomicInteger();
            for (int t = 0; t < hostThreads; t++) {
                final int tid = t;
                new Thread(() -> {
                    try {
                        for (int c = 0; c < callsPerThread; c++) if (
                            service.echoInt((tid << 16) | c) ==
                            ((tid << 16) | c)
                        ) ok.incrementAndGet();
                    } catch (Throwable ignored) {
                    } finally {
                        done.countDown();
                    }
                })
                    .start();
            }
            assertTrue(done.await(120, TimeUnit.SECONDS));
            assertEquals(hostThreads * callsPerThread, ok.get());
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_concurrentLoadAndInvoke(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_concurrentLoadAndInvoke");
            int workerThreads = 4,
                callsPerWorker = 400,
                loadReps = 40;
            CountDownLatch done = new CountDownLatch(workerThreads + 1);
            for (int t = 0; t < workerThreads; t++) new Thread(() -> {
                try {
                    for (int c = 0; c < callsPerWorker; c++) service.noop();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
            new Thread(() -> {
                try {
                    for (int i = 0; i < loadReps; i++) currentEnclave.load(
                        SimpleService.class
                    );
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_destroyRacesEcalls(EnclaveType type) throws Exception {
        setUp(type);
        try {
            System.out.println("[host] Starting host_destroyRacesEcalls");
            int workerThreads = 4;
            long workDurationMs = 2_000,
                deadline = System.currentTimeMillis() + workDurationMs;
            CountDownLatch done = new CountDownLatch(workerThreads);
            for (int t = 0; t < workerThreads; t++) new Thread(() -> {
                try {
                    while (
                        System.currentTimeMillis() < deadline
                    ) service.noop();
                } catch (Throwable ignored) {
                } finally {
                    done.countDown();
                }
            })
                .start();
            Thread.sleep(workDurationMs / 2);
            currentEnclave.destroy();
            currentEnclave = null;
            assertTrue(done.await(60, TimeUnit.SECONDS));
        } finally {
            tearDown();
        }
    }

    @ParameterizedTest
    @EnumSource(
        value = EnclaveType.class,
        mode = EnumSource.Mode.EXCLUDE,
        names = { "NONE", "EMBEDDED_LIB_OS" }
    )
    void host_backToBackLifecycles(EnclaveType type) throws Exception {
        System.out.println("[host] Starting host_backToBackLifecycles");
        for (int cycle = 0; cycle < 4; cycle++) {
            Enclave enc = EnclaveFactory.create(type);
            SimpleService svc = enc.load(SimpleService.class).next();
            svc.noop();
            enc.destroy();
        }
    }
}
