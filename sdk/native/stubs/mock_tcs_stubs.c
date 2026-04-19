/*
 * Stub implementations of the tee_sdk_tcs_* symbols referenced by the
 * GraalVM @CFunction bindings in NativeTcsCache.java. The real
 * implementations live in tee_sdk_wrapper.c and are only linked into
 * the TEE-SDK enclave image. The mock-in-SVM image still contains the
 * @CFunction call sites, so the symbols must resolve at dlopen time.
 *
 * tee_sdk_tcs_is_initialized returns 0 to force EnclavePrologue down
 * the legacy enterAttachThread path; the remaining stubs are unreachable
 * in mock mode but must be defined so the dynamic linker is satisfied.
 */
#include <stdint.h>
#include <stddef.h>

__attribute__((weak)) uint64_t tee_sdk_tcs_lookup(void) { return 0; }
__attribute__((weak)) int      tee_sdk_tcs_register(uint64_t isolate_thread) { (void)isolate_thread; return 0; }
__attribute__((weak)) int      tee_sdk_tcs_is_initialized(void) { return 0; }
__attribute__((weak)) void     tee_sdk_tcs_set_callbacks(void* cb) { (void)cb; }
__attribute__((weak)) void*    tee_sdk_tcs_get_callbacks(void) { return NULL; }
