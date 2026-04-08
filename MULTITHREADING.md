# Multi-Threaded ECALL Support for Teaclave Java TEE SDK

**Author:** Luca Di Bello  
**Date:** April 2026  
**Branch:** `enclave-thread`

This document describes the modifications made to the [Apache Teaclave Java TEE SDK](https://github.com/apache/incubator-teaclave-java-tee-sdk) to enable **concurrent ECALLs from multiple host threads** into a single SGX enclave. The upstream SDK serializes all ECALLs through a GC safepoint rendezvous, which prevents any form of host-side parallelism and introduces two fatal failure modes when background threads exist inside the enclave.

---

## 1. The Problem

### 1.1 Motivation

I was building [Confidential Storm](https://github.com/lucadibello/confidential-storm), a privacy-preserving stream processing system that runs differential privacy algorithms inside SGX enclaves via the Teaclave SDK. The system uses Apache Storm, where multiple executor threads on the host need to make concurrent ECALLs into the same enclave — for example, sending data contributions while a snapshot operation runs in the background.

With the upstream SDK, this is impossible. Every ECALL hangs or crashes if any concurrency is involved.

### 1.2 Root Cause

The upstream `EnclavePrologue.java` calls `CEntryPointActions.enterAttachThread(isolate, true)` on **every ECALL entry**. This GraalVM Substrate VM primitive does three things:

1. Allocates a new `IsolateThread` struct for the calling OS thread
2. Registers it in the isolate's global thread list
3. **Drives a full GC safepoint rendezvous** — spin-waits until every registered IsolateThread checks in

This design causes two distinct failure modes:

| Failure | Trigger | Mechanism | Symptom |
|---------|---------|-----------|---------|
| **Heap corruption** | Background thread in native call (e.g., `SecureRandom` OCALL) | Rendezvous cannot complete; GC runs with inconsistent heap | SIGSEGV or `EOFException` |
| **Permanent hang** | Background thread has exited (zombie IsolateThread) | Rendezvous spin-waits forever on zombie that never checks in | Silent hang — ECALL never returns |

The zombie hang is particularly insidious: it poisons the enclave **permanently** after the first background thread exits, regardless of what the thread did. In production, this manifested as a hang at epoch 3 of the DP pipeline, when enough GC pressure triggered the first post-zombie safepoint.

### 1.3 Why This Isn't a Hardware Limitation

SGX hardware fully supports multi-threaded enclaves. The `enclave_max_thread` configuration (typically 64) allocates that many TCS (Thread Control Structure) slots, each with its own independent stack region. Multiple host threads can enter the enclave simultaneously through separate TCS slots — this is by design.

The limitation is entirely in the **software layer**: GraalVM's Substrate VM isolate threading model, as exposed through the Teaclave SDK's `EnclavePrologue`. The [Lejacon paper](https://doi.org/10.1109/DSN58367.2023.00030) (IEEE DSN 2023, which describes the Teaclave SDK) acknowledges this as a future direction but attributes it imprecisely to SGX itself:

> *"It remains one of the future directions to extend Lejacon such that it allows [...] parallel computing to be performed on both sides."*

Our investigation identifies the precise mechanical cause the paper leaves unspecified.

### 1.4 Experimental Evidence

A full root cause analysis with 14 targeted experiments on real SGX hardware is documented in [`enclave-multithreading-findings.md`](../../enclave-multithreading-findings.md). Key results:

- **`SecureRandom` vs `java.util.Random`**: `SecureRandom.nextGaussian()` routes entropy through a native OCALL with no GC safepoint poll → heap corruption. `java.util.Random.nextGaussian()` is pure Java with implicit safepoint polls → works fine. This isolated `SecureRandom` as the crash trigger for failure mode 1.

- **Thread exit poisons enclave permanently**: A background thread that has *fully exited* before any ECALL is issued still causes a hang. The enclave is poisoned by the thread's mere existence, not its workload. This proved the zombie IsolateThread is the root cause of failure mode 2.

- **Moving work off the background thread doesn't help**: Deferring encryption to the ECALL thread (so the background thread does only pure-Java work) produces the same hang. The fix must address the threading model itself.

---

## 2. The Solution: IsolateThread Pool

### 2.1 Core Idea

Replace `enterAttachThread` (registers new thread + drives safepoint) with `CEntryPointActions.enter(IsolateThread)` (reuses a pre-registered thread — **no safepoint rendezvous**).

Pre-allocate N `IsolateThread` slots at enclave initialization. Each ECALL claims a slot from a lock-free pool, enters via `enter()`, and releases the slot on exit. No new thread registration ever happens after init.

### 2.2 Initialization Sequence

At enclave creation time (after `nativeSvmAttachIsolate`):

1. **Spawn N helper pthreads** inside the enclave via `pthread_create` (through SGX's pthread shim backed by OCALLs)
2. Each helper calls `enterAttachThread`, which registers a new `IsolateThread` — this happens sequentially, before any concurrent ECALLs exist, so the safepoint rendezvous is safe
3. Each helper writes its `IsolateThread` handle to a shared output array, then **parks** on a condition variable
4. The handles are stored in a **C-side lock-free pool** (64-bit bitmask + CAS operations)

The helpers must stay parked (not exit) because GraalVM's thread list requires the backing OS thread to remain alive for each registered `IsolateThread`.

### 2.3 ECALL Entry (Prologue)

```
EnclavePrologue.enter(isolate):
  1. Call C function tee_sdk_pool_claim() via @CFunction(NO_TRANSITION)
     → Atomically find lowest set bit in bitmask, clear it via CAS
     → Return the IsolateThread handle at that index
  2. CEntryPointActions.enter(slot)
     → Sets current VM thread, transitions Native→Java
     → NO new registration, NO safepoint rendezvous
  3. Execute the Java service method
```

### 2.4 ECALL Exit (Epilogue)

```
EnclaveEpilogue.leave():
  1. Capture current IsolateThread handle
  2. CEntryPointActions.leave()
  3. Call C function tee_sdk_pool_release(handle) via @CFunction(NO_TRANSITION)
     → Find the handle's index, set the bit via CAS
```

### 2.5 Shutdown

```
enclave_svm_release_pool_threads():
  1. Signal all parked helpers (set shutdown flag, signal condition variable)
  2. Each helper calls java_detach_helper_thread → enter(thread) + leaveDetachThread()
     → Cleanly removes IsolateThread from GraalVM's thread list
  3. pthread_join all helpers — blocks until all detaches complete
  4. Proceed with graal_detach_all_threads_and_tear_down_isolate()
```

The `pthread_join` step is critical: without it, helpers may still be mid-detach when isolate teardown begins, causing a deadlock.

---

## 3. The Stack Bounds Problem

### 3.1 The Issue

`CEntryPointActions.enter()` succeeds, but the first Java frame after entry checks the stack pointer against `StackBase`/`StackEnd` recorded in the `IsolateThread` struct. These bounds were set when the **helper pthread** registered the `IsolateThread` — they reflect that helper's TCS stack region.

When a different ECALL thread (assigned to a different TCS slot by SGX hardware) reuses the handle, its stack pointer is in a completely different memory region → immediate `StackOverflowError: "A call from native code to Java code provided the wrong IsolateThread"`.

### 3.2 Approaches That Failed

1. **Calling `StackOverflowCheck.initialize()` from the prologue**: GraalVM's `@Uninterruptible` static analysis rejects `ImageSingletons.lookup()` and `KnownIntrinsics.readStackPointer()` anywhere in the prologue's call graph. Build error: "must not call" chain.

2. **Extracting to a separate method with `calleeMustBe = false`**: GraalVM requires prologue classes to declare exactly one static method. Any additional method (even a helper) violates this constraint.

3. **TCS-based matching** (map each ECALL thread's SP to the IsolateThread registered from the same TCS): Each helper pthread is parked and holds a TCS slot. When ECALL threads enter, SGX assigns them to *different* TCS slots (the free ones). No ECALL thread ever gets the same TCS as a helper → no match found.

4. **`pthread_getattr_np` on ECALL threads**: ECALL threads enter through SGX's TCS mechanism, not `pthread_create`. `pthread_getattr_np` doesn't work for them — it returns garbage or crashes.

### 3.3 The Fix: Wide Stack Bounds via Modified `pthread_attr_getstack`

GraalVM's `StackOverflowCheck.initialize()` calls `pthread_attr_getstack` (shimmed by `tee_sdk_symbol.c` in the SGX enclave) to get the current thread's stack bounds. The shim reads from SGX's `thread_data` structure, which is TCS-specific.

The fix introduces a global flag `g_use_wide_stack_bounds`. When set (before spawning helper pthreads), the shim returns a **512MB range** centered on the current TCS stack instead of the actual bounds. This range is large enough to cover **all** TCS stacks in the enclave.

When GraalVM's `StackOverflowCheck.initialize()` runs during each helper's `enterAttachThread`, it records these wide bounds in the `IsolateThread` struct. Any ECALL thread entering through any TCS will have a stack pointer within these bounds, so the stack overflow check passes.

This effectively disables stack overflow detection for pool-managed `IsolateThread`s, which is acceptable for SGX enclaves where stack sizes are hardware-constrained by the TCS configuration.

---

## 4. Implementation: Modified Files

### New Files

| File | Purpose |
|------|---------|
| `sdk/enclave/.../NativePool.java` | `@CFunction(NO_TRANSITION)` declarations for C-side pool `claim()`/`release()` |
| `sdk/enclave/.../EnclaveEpilogue.java` | Custom epilogue that releases pool slot after `leave()` |

### Modified Files

| File | Change |
|------|--------|
| `sdk/enclave/.../EnclavePrologue.java` | Replaced `enterAttachThread` with `NativePool.claim()` + `enter()`. Falls back to `enterAttachThread` when pool is not initialized (legacy mode). |
| `sdk/enclave/.../IsolateThreadPool.java` | Simplified to track initialization flag and store handles. Stack matching and bitmask moved to C. |
| `sdk/enclave/.../EnclaveEntry.java` | Added `initThreadPool`, `attachHelperThread`, `detachHelperThread` `@CEntryPoint` methods |
| `sdk/enclave/.../EnclaveFeature.java` | Build-time initialization for `IsolateThreadPool` and `EnclaveEpilogue` |
| `sdk/enclave/.../tee_sdk_wrapper.c` | Helper pthread lifecycle, lock-free pool (claim/release via CAS bitmask), shutdown with `pthread_join` |
| `sdk/enclave/.../tee_sdk_symbol.c` | Wide stack bounds in `pthread_attr_getstack` when `g_use_wide_stack_bounds` is set |
| `sdk/native/.../tee_sdk_enclave.edl` | New trusted ECALLs: `enclave_svm_preallocate_threads`, `enclave_svm_release_pool_threads` |
| `sdk/native/include/enc_exported_symbol.h` | Declarations for `java_init_thread_pool`, `java_attach_helper_thread`, `java_detach_helper_thread` |
| `sdk/host/.../TeeSdkEnclave.java` | Calls `nativePreallocateThreads` after isolate creation, `nativeReleasePoolThreads` before destroy |
| `sdk/host/.../jni_tee_sdk_svm.c` | JNI implementations for preallocate and release native methods |

---

## 5. Test Results

Tests run on real SGX hardware (TEE_SDK mode):

| Test | Description | Result | Notes |
|------|-------------|--------|-------|
| **test0** | Single ECALL + clean destroy | **PASS** | Pool init → ECALL → helper shutdown → isolate teardown, all clean |
| **test1** | 4 host threads × 200 concurrent ECALLs | **PASS** | **Key result**: concurrent host-side ECALLs into a single enclave work correctly |
| **test2** | Background GC-pressure thread + concurrent ECALLs | **PASS** (ECALLs) | All 400 ECALLs complete. Destroy hangs — zombie from `Thread.start()` inside enclave (see limitations) |
| **test3** | Production DP pattern: snapshot bg thread + concurrent contributions | Timeout | Zombie IsolateThread from bg thread poisons the isolate |
| **test4** | `SecureRandom` background thread + concurrent ECALLs | **FAIL** | `SecureRandom` OCALL has no safepoint poll — heap corruption (see limitations) |

---

## 6. What This Fixes vs. Known Limitations

### What the pool fixes

- **Concurrent host-side ECALLs**: Multiple host threads can now make ECALLs simultaneously into the same enclave without blocking each other or triggering safepoint rendezvous. This was the primary goal.
- **Clean enclave lifecycle**: Pool helpers are properly shut down and detached before isolate teardown, preventing the destroy hang.
- **No application-level code changes required**: The pool is transparent — existing service interfaces and host code work unchanged.

### Known limitations (not addressed by this fix)

- **Zombie IsolateThreads from `Thread.start()` inside the enclave** (tests 2–3): Threads created via `new Thread().start()` inside the enclave enter through `enterAttachThread` (the pool is already initialized, but `Thread.start()` uses GraalVM's internal thread creation path, not the pool prologue). When these threads exit, they leave zombie `IsolateThread`s that eventually cause hangs. Fix requires either a `Thread` substitution that calls `detachThread()` on exit, or avoiding in-enclave thread creation entirely.

- **`SecureRandom` OCALL safepoint gap** (test 4): The entropy acquisition path (`SecureRandom` → `NativePRNGSubstitutions` → OCALL → `sgx_read_rand`) contains no GC safepoint poll. Any GC triggered while a thread is in this call corrupts the heap. Workaround: use `java.util.Random` instead of `SecureRandom` where cryptographic randomness is not strictly required (e.g., AEAD nonces where uniqueness, not unpredictability, is the requirement).

---

## 7. Configuration

The pool size is determined by `java_enclave_configure.json`:

```json
{
  "enclave_max_thread": 64,
  "thread_pool_size_reserve": 4
}
```

Pool size = `(enclave_max_thread - reserve) / 2`

The division by 2 accounts for TCS slot consumption: each pool slot requires a parked helper pthread holding one TCS slot. With N pool slots, N TCS slots are used by helpers and up to N TCS slots are available for ECALL threads. The reserve leaves headroom for internal use (isolate creation thread, etc.).

With the default `enclave_max_thread: 64` and `reserve: 4`, this yields **30 pool slots** supporting up to 30 concurrent ECALLs.

---

## 8. Building

```bash
cd sdk
mvn clean install -DskipTests
```

The build produces the modified enclave SDK libraries. Applications link against them normally — the pool mechanism is entirely internal to the SDK.
