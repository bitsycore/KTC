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

/** ═══════════════════════════ Stack Trace ══════════════════════════ */

/* Maximum number of frames to capture */
#define KTC_ST_MAX_FRAMES  32
/* Frames to skip: ktc_stacktrace_print itself + the generated error() wrapper */
#define KTC_ST_SKIP_FRAMES  2

#if defined(_WIN32)

#include <dbghelp.h>
/* MSVC: auto-link. MinGW: DbgHelp is loaded dynamically, no -ldbghelp needed. */
#ifdef _MSC_VER
#   pragma comment(lib, "dbghelp.lib")
#endif

/* DbgHelp function pointer types — loaded at runtime to avoid MinGW import issues. */
typedef BOOL (WINAPI *PFN_SymInitialize)(HANDLE, PCSTR, BOOL);
typedef BOOL (WINAPI *PFN_SymFromAddr)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO);
typedef BOOL (WINAPI *PFN_SymGetLineFromAddr64)(HANDLE, DWORD64, PDWORD, PIMAGEHLP_LINE64);
typedef BOOL (WINAPI *PFN_SymCleanup)(HANDLE);

static const char* ktc_st_basename(const char* inPath)
{
    const char* vSlash  = strrchr(inPath, '\\');
    const char* vFSlash = strrchr(inPath, '/');
    const char* vLast   = vSlash > vFSlash ? vSlash : vFSlash;
    return vLast ? vLast + 1 : inPath;
}

void ktc_stacktrace_print(const char* inMessage, int inMessageLen)
{
    fprintf(stderr, "Exception in thread \"main\" kotlin.Error: %.*s\n",
        inMessageLen, inMessage);

    void*  vFrames[KTC_ST_MAX_FRAMES];
    USHORT vFrameCount = CaptureStackBackTrace(
        KTC_ST_SKIP_FRAMES, KTC_ST_MAX_FRAMES, vFrames, NULL
    );

    /* Load DbgHelp dynamically — works with both MSVC and MinGW. */
    HMODULE vDbgHelp = LoadLibraryA("dbghelp.dll");
    if (!vDbgHelp) {
        for (USHORT i = 0; i < vFrameCount; i++)
            fprintf(stderr, "\tat ??(Unknown Source)\n");
        return;
    }

    PFN_SymInitialize        vSymInitialize        = (PFN_SymInitialize)       GetProcAddress(vDbgHelp, "SymInitialize");
    PFN_SymFromAddr          vSymFromAddr          = (PFN_SymFromAddr)         GetProcAddress(vDbgHelp, "SymFromAddr");
    PFN_SymGetLineFromAddr64 vSymGetLineFromAddr64 = (PFN_SymGetLineFromAddr64)GetProcAddress(vDbgHelp, "SymGetLineFromAddr64");
    PFN_SymCleanup           vSymCleanup           = (PFN_SymCleanup)          GetProcAddress(vDbgHelp, "SymCleanup");

    HANDLE vProcess = GetCurrentProcess();
    if (vSymInitialize) vSymInitialize(vProcess, NULL, TRUE);

    /* SYMBOL_INFO needs a trailing name buffer inline. */
    char vSymBuf[sizeof(SYMBOL_INFO) + 255];
    SYMBOL_INFO* vSym = (SYMBOL_INFO*)vSymBuf;
    memset(vSymBuf, 0, sizeof(vSymBuf));
    vSym->SizeOfStruct = sizeof(SYMBOL_INFO);
    vSym->MaxNameLen   = 255;

    IMAGEHLP_LINE64 vLine;
    memset(&vLine, 0, sizeof(vLine));
    vLine.SizeOfStruct = sizeof(IMAGEHLP_LINE64);

    for (USHORT i = 0; i < vFrameCount; i++) {
        DWORD64 vDisp64 = 0;
        DWORD   vDisp32 = 0;
        BOOL vHasSym  = vSymFromAddr          && vSymFromAddr(vProcess, (DWORD64)vFrames[i], &vDisp64, vSym);
        BOOL vHasLine = vSymGetLineFromAddr64 && vSymGetLineFromAddr64(vProcess, (DWORD64)vFrames[i], &vDisp32, &vLine);

        const char* vFunc = (vHasSym && vSym->Name[0]) ? vSym->Name : "??";

        if (vHasLine)
            fprintf(stderr, "\tat %s(%s:%lu)\n", vFunc, ktc_st_basename(vLine.FileName), vLine.LineNumber);
        else
            fprintf(stderr, "\tat %s(Unknown Source)\n", vFunc);
    }

    if (vSymCleanup) vSymCleanup(vProcess);
    FreeLibrary(vDbgHelp);
}

#elif defined(__GNUC__) || defined(__clang__)
/* POSIX: Linux and macOS.
 * Requires -rdynamic (Linux) or default export policy (macOS) for symbol names.
 * Requires -ldl on Linux for dladdr(). */

#include <execinfo.h>
#include <dlfcn.h>

void ktc_stacktrace_print(const char* message, int message_len)
{
    fprintf(stderr, "Exception in thread \"main\" kotlin.Error: %.*s\n",
        message_len, message);

    void* frames[KTC_ST_MAX_FRAMES];
    int   frame_count = backtrace(frames, KTC_ST_MAX_FRAMES);
    /* backtrace_symbols is a fallback when dladdr finds nothing */
    char** syms = backtrace_symbols(frames, frame_count);

    int start = KTC_ST_SKIP_FRAMES;
    if (start >= frame_count) start = 0;

    for (int i = start; i < frame_count; i++) {
        Dl_info info;
        /* dladdr gives the cleanest function name on both Linux and macOS */
        if (dladdr(frames[i], &info) && info.dli_sname && info.dli_sname[0])
            fprintf(stderr, "\tat %s(Unknown Source)\n", info.dli_sname);
        else if (syms && syms[i])
            fprintf(stderr, "\tat %s\n", syms[i]);
        else
            fprintf(stderr, "\tat ??(Unknown Source)\n");
    }

    if (syms) free(syms);
}

#else

/* Unsupported platform: print message only, no frames. */
void ktc_stacktrace_print(const char* message, int message_len)
{
    fprintf(stderr, "Exception in thread \"main\" kotlin.Error: %.*s\n",
        message_len, message);
    fprintf(stderr, "\t(stack trace unavailable on this platform)\n");
}

#endif

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