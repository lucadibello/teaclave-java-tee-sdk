#include <errno.h>
#include <sys/types.h>
#include <sys/random.h>
#include <stddef.h>

/*
 * Mock-in-SVM stub for tee_sdk_enclave_read_rand.
 *
 * The real implementation lives in tee_sdk_wrapper.c (SGX enclave only) and
 * calls sgx_read_rand (RDRAND). For the mock-in-SVM build we don't have SGX,
 * so we service the entropy request via getrandom(2) on the host.
 *
 * Marked weak so it is overridden by the real implementation when the
 * TEE-SDK image is linked (tee_sdk_wrapper.c in the enclave).
 *
 * Returns 0 on success, non-zero on failure (matching sgx_status_t semantics).
 */
__attribute__((weak))
int tee_sdk_enclave_read_rand(void* data, int size) {
    if (data == NULL || size <= 0) return -1;
    unsigned char* p = (unsigned char*)data;
    size_t remaining = (size_t)size;
    while (remaining > 0) {
        ssize_t n = getrandom(p, remaining, 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        remaining -= (size_t)n;
    }
    return 0;
}
