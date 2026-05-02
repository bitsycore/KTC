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