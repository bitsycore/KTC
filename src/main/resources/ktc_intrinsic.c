#include "ktc_intrinsic.h"

void ktc_srand(
    ktc_ULong* state,
    ktc_ULong* inc,
    ktc_ULong seed
) {
    *state = 0;
    *inc = (seed << 1u) | 1u;

    ktc_rand(state, inc);

    *state += seed;

    ktc_rand(state, inc);
}

ktc_UInt ktc_rand(
    ktc_ULong* state,
    ktc_ULong* inc
) {
    ktc_ULong oldstate = *state;

    *state = oldstate * 6364136223846793005ULL + *inc;

    ktc_UInt xorshifted =
        (ktc_UInt)(((oldstate >> 18u) ^ oldstate) >> 27u);

    ktc_UInt rot = oldstate >> 59u;

    return (xorshifted >> rot) |
           (xorshifted << ((-rot) & 31));
}

ktc_UInt ktc_rand_range(
    ktc_ULong* state,
    ktc_ULong* inc,
    ktc_UInt bound
) {
    if (bound == 0) return 0;

    ktc_UInt threshold = -bound % bound;

    for (;;) {
        ktc_UInt r = ktc_rand(state, inc);

        if (r >= threshold)
            return r % bound;
    }
}

ktc_Double ktc_time_seconds(void)
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

    return (ktc_Double)counter.QuadPart / (ktc_Double)freq.QuadPart;

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (ktc_Double)ts.tv_sec + (ktc_Double)ts.tv_nsec / 1e9;
#endif
}

ktc_ULong ktc_time_ms(void)
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

    return (ktc_ULong)((counter.QuadPart * 1000ULL) / freq.QuadPart);

#else
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);

    return (ktc_ULong)ts.tv_sec * 1000ULL +
           (ktc_ULong)ts.tv_nsec / 1000000ULL;
#endif
}

void ktc_time_sleep_ms(ktc_UInt ms)
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

void ktc_time_sleep_seconds(ktc_Double seconds)
{
#if defined(_WIN32)
    ktc_time_sleep_ms((ktc_UInt)(seconds * 1000.0));
#else
    struct timespec req;
    req.tv_sec  = (time_t)seconds;
    req.tv_nsec = (long)((seconds - (ktc_Double)req.tv_sec) * 1e9);
    while (nanosleep(&req, &req) == -1 && errno == EINTR) {}
#endif
}

/* ═══════════════════════════ Initialization ═══════════════════════ */

static void ktc_console_init(void) {
#if defined(_WIN32)
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
}

void ktc_mainInit(void) {
    ktc_console_init();
}