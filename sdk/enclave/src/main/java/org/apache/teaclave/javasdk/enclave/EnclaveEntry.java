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

package org.apache.teaclave.javasdk.enclave;

import org.apache.teaclave.javasdk.enclave.c.EnclaveEnvironment.CallBacks;
import org.apache.teaclave.javasdk.enclave.c.EnclaveEnvironment.EncData;
import org.apache.teaclave.javasdk.enclave.framework.LoadServiceInvoker;
import org.apache.teaclave.javasdk.enclave.framework.ServiceMethodInvoker;
import org.apache.teaclave.javasdk.enclave.framework.UnloadServiceInvoker;
import com.oracle.svm.core.IsolateArgumentParser;
import com.oracle.svm.core.SubstrateGCOptions;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.c.function.CEntryPointActions;
import com.oracle.svm.core.c.function.CEntryPointCreateIsolateParameters;
import com.oracle.svm.core.c.function.CEntryPointNativeFunctions;
import com.oracle.svm.core.c.function.CEntryPointOptions;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Isolate;
import org.graalvm.nativeimage.IsolateThread;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.function.CEntryPoint;
import org.graalvm.nativeimage.c.type.CCharPointerPointer;
import org.graalvm.nativeimage.c.type.CTypeConversion;
import org.graalvm.word.Pointer;
import org.graalvm.word.WordFactory;

/**
 * This class defines the entry points for native image (shared library) deployed in TEE enclave.
 */
public class EnclaveEntry {
    private static volatile CallBacks callBackMethods;

    public static CallBacks getCallBackMethods() {
        return callBackMethods;
    }

    @Uninterruptible(reason = "Thread state not set up yet.", calleeMustBe = false)
    @CEntryPointOptions(prologue = CEntryPointOptions.NoPrologue.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    @CEntryPoint(name = "create_isolate_with_params")
    public static int createIsolateWithParams(int argc, CCharPointerPointer argv, CEntryPointNativeFunctions.IsolatePointer isolatePr, CEntryPointNativeFunctions.IsolateThreadPointer thread) {
        CEntryPointCreateIsolateParameters args = StackValue.get(CEntryPointCreateIsolateParameters.class);
        args.setVersion(4);
        args.setArgc(argc);
        args.setArgv(argv);
        args.setIgnoreUnrecognizedArguments(false);
        args.setExitWhenArgumentParsingFails(true);
        int result = CEntryPointActions.enterCreateIsolate(args);
        if (result != 0) {
            return result;
        } else {
            if (isolatePr.isNonNull()) {
                isolatePr.write(CurrentIsolate.getIsolate());
            }
            if (thread.isNonNull()) {
                thread.write(CurrentIsolate.getCurrentThread());
            }
            SubstrateGCOptions.MaxHeapSize.update((long) IsolateArgumentParser.getIntOptionValue(IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MaxHeapSize)));
            SubstrateGCOptions.MinHeapSize.update((long) IsolateArgumentParser.getIntOptionValue(IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MinHeapSize)));
            SubstrateGCOptions.MaxNewSize.update((long) IsolateArgumentParser.getIntOptionValue(IsolateArgumentParser.getOptionIndex(SubstrateGCOptions.MaxNewSize)));
            return CEntryPointActions.leave();
        }
    }

    @SuppressWarnings("unused")
    // Align with head define file enc_exported_symbol.h if it changes.
    @CEntryPoint(name = "java_loadservice_invoke")
    @CEntryPointOptions(prologue = EnclavePrologue.class, epilogue = EnclaveEpilogue.class)
    public static int loadService(Isolate isolate, EncData input, EncData result, CallBacks callBacks) {
        callBackMethods = callBacks;
        int retCode = 0;
        try {
            InvocationWrapper.invoke(input, result, callBacks, ImageSingletons.lookup(LoadServiceInvoker.class));
        } catch (Throwable t) {
            retCode = handleFrameworkException(t);
        }
        return retCode;
    }

    @SuppressWarnings("unused")
    // Align with head define file enc_exported_symbol.h if it changes.
    @CEntryPoint(name = "java_unloadservice_invoke")
    @CEntryPointOptions(prologue = EnclavePrologue.class, epilogue = EnclaveEpilogue.class)
    public static int unloadService(Isolate isolate, EncData input, EncData result, CallBacks callBacks) {
        callBackMethods = callBacks;
        int retCode = 0;
        try {
            InvocationWrapper.invoke(input, result, callBacks, ImageSingletons.lookup(UnloadServiceInvoker.class));
        } catch (Throwable t) {
            retCode = handleFrameworkException(t);
        }
        return retCode;
    }

    @SuppressWarnings("unused")
    // Align with head define file enc_exported_symbol.h if it changes.
    @CEntryPoint(name = "java_enclave_invoke")
    @CEntryPointOptions(prologue = EnclavePrologue.class, epilogue = EnclaveEpilogue.class)
    public static int javaEnclaveInvoke(Isolate isolate, EncData input, EncData result, CallBacks callBacks) {
        callBackMethods = callBacks;
        int retCode = 0;
        try {
            InvocationWrapper.invoke(input, result, callBacks, ImageSingletons.lookup(ServiceMethodInvoker.class));
        } catch (Throwable t) {
            retCode = handleFrameworkException(t);
        }
        return retCode;
    }

    /**
     * Initialize the IsolateThread pool with pre-registered handles.
     * Called from the C wrapper after helper pthreads have each called enterAttachThread
     * and saved their IsolateThread handles.
     *
     * @param isolate  the isolate (used by prologue)
     * @param count    number of pre-registered IsolateThread handles
     * @param handles  pointer to array of IsolateThread handles (as uint64_t values)
     * @param bufSize  size of the handles buffer in bytes (for EDL size validation)
     * @return 0 on success
     */
    @SuppressWarnings("unused")
    @CEntryPoint(name = "java_init_thread_pool")
    @CEntryPointOptions(prologue = EnclavePrologue.class)
    public static int initThreadPool(Isolate isolate, int count, Pointer handles, long bufSize) {
        IsolateThreadPool.initialize(count, handles);
        // NOTE: uses the default epilogue (leave), NOT EnclaveEpilogue.
        // This ECALL enters via enterAttachThread (pool not yet initialized),
        // so the epilogue must NOT call IsolateThreadPool.release() — the
        // IsolateThread was not claimed from the pool.
        return 0;
    }

    /**
     * Attach the calling thread to the isolate and return its IsolateThread handle.
     * This is called from helper pthreads spawned during pool initialization.
     * The prologue calls enterAttachThread which registers a new IsolateThread for the
     * calling (helper) pthread. We then read the handle via CurrentIsolate and write it
     * to the output pointer.
     *
     * @param isolate    the isolate (used by prologue's enterAttachThread)
     * @param handleOut  pointer to a uint64_t where the IsolateThread handle is written
     * @return 0 on success
     */
    @SuppressWarnings("unused")
    @CEntryPoint(name = "java_attach_helper_thread")
    @CEntryPointOptions(prologue = EnclavePrologue.class)
    public static int attachHelperThread(Isolate isolate, Pointer handleOut) {
        // The prologue has already called enterAttachThread for this pthread.
        // Read the IsolateThread handle that was just created.
        IsolateThread current = CurrentIsolate.getCurrentThread();
        handleOut.writeLong(0, current.rawValue());
        // NOTE: we use the default epilogue (leave) here, NOT EnclaveEpilogue.
        // The default epilogue calls CEntryPointActions.leave() which marks the
        // IsolateThread as inactive but keeps it in the thread list.
        // This is intentional: we want the IsolateThread to remain registered
        // so the pool can reuse it via enter().
        return 0;
    }

    /**
     * Detach the calling helper thread's IsolateThread from the isolate.
     * Called during shutdown to cleanly remove the helper's IsolateThread from
     * GraalVM's thread list so isolate teardown doesn't hang waiting for it.
     *
     * Uses enter() to re-enter the helper's IsolateThread (STATUS_IN_NATIVE → STATUS_IN_JAVA),
     * then leaveDetachThread() to remove it from the thread list and free it.
     *
     * @param thread  the helper's IsolateThread handle (passed from C code)
     * @return 0 on success
     */
    @SuppressWarnings("unused")
    @CEntryPoint(name = "java_detach_helper_thread")
    @CEntryPointOptions(prologue = CEntryPointOptions.NoPrologue.class, epilogue = CEntryPointOptions.NoEpilogue.class)
    @Uninterruptible(reason = "Thread state not set up correctly for normal prologue/epilogue.", calleeMustBe = false)
    public static int detachHelperThread(IsolateThread thread) {
        int code = CEntryPointActions.enter(thread);
        if (code != 0) {
            return code;
        }
        return CEntryPointActions.leaveDetachThread();
    }

    private static int handleFrameworkException(Throwable t) {
        if (callBackMethods.isNonNull() && callBackMethods.getExceptionHandler().isNonNull()) {
            StringBuilder stacktraceSB = new StringBuilder();
            for (StackTraceElement se : t.getStackTrace()) {
                stacktraceSB.append(se.toString()).append("\n");
            }
            try (
                    CTypeConversion.CCharPointerHolder stacktrace = CTypeConversion.toCString(stacktraceSB.toString());
                    CTypeConversion.CCharPointerHolder errMsg = CTypeConversion.toCString(t.getMessage());
                    CTypeConversion.CCharPointerHolder exception = CTypeConversion.toCString(t.getClass().toString())) {
                callBackMethods.getExceptionHandler().invoke(errMsg.get(), stacktrace.get(), exception.get());
            }
        } else {
            t.printStackTrace();
        }
        return 1;
    }
}
