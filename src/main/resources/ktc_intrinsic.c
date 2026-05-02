#include "ktc_intrinsic.h"

static uint64_t state = 0x853c49e6748fea9bULL;
static uint64_t inc   = 0xda3e39cb94b95bdbULL;

void ktc_srand(uint64_t seed) {
    state = 0;
    inc = (seed << 1u) | 1u;
    ktc_rand();
    state += seed;
    ktc_rand();
}

uint32_t ktc_rand(void) {
    uint64_t oldstate = state;
    state = oldstate * 6364136223846793005ULL + inc;

    uint32_t xorshifted = (uint32_t)(((oldstate >> 18u) ^ oldstate) >> 27u);
    uint32_t rot = oldstate >> 59u;

    return (xorshifted >> rot) | (xorshifted << ((-rot) & 31));
}

uint32_t ktc_rand_range(uint32_t bound) {
    uint32_t threshold = -bound % bound;

    for (;;) {
        uint32_t r = ktc_rand();
        if (r >= threshold)
            return r % bound;
    }
}

double ktc_time_seconds(void)
{
#if defined(_WIN32)
    static LARGE_INTEGER freq;
    static int initialized = 0;

    if (!initialized) {
        QueryPerformanceFrequency(&freq);
        initialized = 1;
    }

    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);

    return (double)counter.QuadPart / (double)freq.QuadPart;

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
#endif
}

uint64_t ktc_time_ms(void)
{
#if defined(_WIN32)
    static LARGE_INTEGER freq;
    static int initialized = 0;

    if (!initialized) {
        QueryPerformanceFrequency(&freq);
        initialized = 1;
    }

    LARGE_INTEGER counter;
    QueryPerformanceCounter(&counter);

    return (uint64_t)((counter.QuadPart * 1000ULL) / freq.QuadPart);

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (uint64_t)ts.tv_sec * 1000ULL +
           (uint64_t)ts.tv_nsec / 1000000ULL;
#endif
}

void ktc_time_sleep_ms(uint32_t ms)
{
#if defined(_WIN32)

    Sleep(ms);

#else

    struct timespec req;
    req.tv_sec  = ms / 1000;
    req.tv_nsec = (ms % 1000) * 1000000L;
    while (nanosleep(&req, &req) == -1 && errno == EINTR) {}

#endif
}

void ktc_time_sleep_seconds(double seconds)
{
    ktc_time_sleep_ms((uint32_t)(seconds * 1000.0));
}