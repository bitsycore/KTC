#include "ktc_intrinsic.h"

/* ═══════════════════════════ Rand ══════════════════════════ */

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

/* ═══════════════════════════ Time ══════════════════════════ */

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

/* ═══════════════════════════ Stack Trace ══════════════════════════ */

/* Maximum number of frames to capture */
#define KTC_ST_MAX_FRAMES  32
/* Frames to skip: ktc_stacktrace_print itself + the generated error() wrapper */
#define KTC_ST_SKIP_FRAMES  2

#if defined(_WIN32)

#include <dbghelp.h>

/* Return just the filename from a full path (handles both / and \). */
static const char* ktc_st_basename(const char* inPath)
{
    const char* p = inPath;
    const char* last = inPath;
    while (*p) {
        if (*p == '/' || *p == '\\') last = p + 1;
        p++;
    }
    return last;
}

/* Format addr2line location "full/path/file.c:42" → "file.c:42". */
static void ktc_st_format_loc(const char* inRaw, char* outBuf, int inBufSize)
{
    /* Find the last colon that precedes digits — the line-number separator. */
    const char* vColon = NULL;
    for (const char* p = inRaw; *p; p++) {
        if (*p == ':' && *(p + 1) >= '0' && *(p + 1) <= '9')
            vColon = p;
    }
    if (!vColon) { snprintf(outBuf, inBufSize, "%s", inRaw); return; }

    char vPath[512];
    int  vN = (int)(vColon - inRaw);
    if (vN >= (int)sizeof(vPath)) vN = (int)sizeof(vPath) - 1;
    memcpy(vPath, inRaw, vN);
    vPath[vN] = '\0';
    snprintf(outBuf, inBufSize, "%s%s", ktc_st_basename(vPath), vColon);
}

#if defined(__MINGW32__) || defined(__MINGW64__)
/*
 * MinGW: DbgHelp only reads PDB symbols; MinGW/GCC produces DWARF.
 * addr2line (shipped with MinGW binutils) reads DWARF natively.
 * Compile with -g for function names and file:line to be available.
 *
 * ASLR: Windows updates the in-memory PE header's ImageBase to the runtime
 * address, so we must read ImageBase from the on-disk file to get the static
 * value needed for: static_addr = runtime_addr - runtime_base + static_base.
 */

/** Read the static ImageBase from the exe file on disk (not in-memory PE, which
 *  Windows rewrites to the runtime load address under ASLR). */
static ULONGLONG ktc_st_disk_image_base(const char* inExe)
{
    HANDLE hf = CreateFileA(inExe, GENERIC_READ, FILE_SHARE_READ,
                            NULL, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (hf == INVALID_HANDLE_VALUE) return 0;

    IMAGE_DOS_HEADER dos;
    DWORD n;
    if (!ReadFile(hf, &dos, sizeof(dos), &n, NULL) || dos.e_magic != IMAGE_DOS_SIGNATURE)
        { CloseHandle(hf); return 0; }

    SetFilePointer(hf, dos.e_lfanew, NULL, FILE_BEGIN);
    IMAGE_NT_HEADERS nt;
    if (!ReadFile(hf, &nt, sizeof(nt), &n, NULL) || nt.Signature != IMAGE_NT_SIGNATURE)
        { CloseHandle(hf); return 0; }

    CloseHandle(hf);
    return (ULONGLONG)nt.OptionalHeader.ImageBase;
}

void ktc_stacktrace_print(const char* inMessage, int inMessageLen)
{
    fprintf(stderr, "Exception in thread \"main\" kotlin.Error: %.*s\n",
        inMessageLen, inMessage);

    void* vFrames[KTC_ST_MAX_FRAMES];
    int   vFrameCount = (int)CaptureStackBackTrace(
        KTC_ST_SKIP_FRAMES, KTC_ST_MAX_FRAMES, vFrames, NULL
    );
    if (vFrameCount == 0) return;

    char vExe[MAX_PATH];
    GetModuleFileNameA(NULL, vExe, sizeof(vExe));

    ULONGLONG runtimeBase = (ULONGLONG)GetModuleHandleA(NULL);
    ULONGLONG staticBase  = ktc_st_disk_image_base(vExe);
    if (staticBase == 0) staticBase = runtimeBase; /* fallback: no correction */

    /* imageSize from the in-memory header — still valid for range check. */
    IMAGE_DOS_HEADER* dos  = (IMAGE_DOS_HEADER*)GetModuleHandleA(NULL);
    IMAGE_NT_HEADERS* nt   = (IMAGE_NT_HEADERS*)((BYTE*)dos + dos->e_lfanew);
    ULONGLONG imageSize    = (ULONGLONG)nt->OptionalHeader.SizeOfImage;

    /* Only include frames inside the main exe; apply ASLR correction. */
    int vExeCount = 0;
    char vCmd[4096];
    int  vPos = snprintf(vCmd, sizeof(vCmd), "addr2line -f -e \"%s\"", vExe);

    for (int i = 0; i < vFrameCount; i++) {
        ULONGLONG frameAddr = (ULONGLONG)vFrames[i];
        if (frameAddr >= runtimeBase && frameAddr < runtimeBase + imageSize) {
            ULONGLONG corrected = frameAddr - runtimeBase + staticBase;
            if (vPos < (int)sizeof(vCmd) - 24)
                vPos += snprintf(vCmd + vPos, sizeof(vCmd) - vPos, " %llx",
                                 (unsigned long long)corrected);
            vExeCount++;
        }
    }

    if (vExeCount == 0) { fprintf(stderr, "\tat ??(Unknown Source)\n"); return; }

    if (vPos < (int)sizeof(vCmd) - 8)
        strncat(vCmd, " 2>NUL", sizeof(vCmd) - (size_t)vPos - 1);

    /* _popen/_pclose must be used explicitly: popen is hidden by __STRICT_ANSI__
     * which -std=c11 activates. */
    FILE* vFp = _popen(vCmd, "r");
    if (!vFp) { fprintf(stderr, "\tat ??(Unknown Source)\n"); return; }

    char vFunc[256], vFileLine[512], vLoc[256];
    for (int i = 0; i < vExeCount; i++) {
        if (!fgets(vFunc,     sizeof(vFunc),     vFp)) break;
        if (!fgets(vFileLine, sizeof(vFileLine), vFp)) break;
        vFunc[strcspn(vFunc, "\r\n")]         = '\0';
        vFileLine[strcspn(vFileLine, "\r\n")] = '\0';

        /* Skip frames with no symbol info — they are CRT/startup internals. */
        if (vFunc[0] == '?' && vFunc[1] == '?') continue;

        if (vFileLine[0] == '?' && vFileLine[1] == '?')
            fprintf(stderr, "\tat %s(Unknown Source)\n", vFunc);
        else {
            ktc_st_format_loc(vFileLine, vLoc, sizeof(vLoc));
            fprintf(stderr, "\tat %s(%s)\n", vFunc, vLoc);
        }
    }
    _pclose(vFp);
}

#else
/*
 * MSVC: DbgHelp reads PDB symbols. Load dynamically so no .lib is needed
 * at link time — dbghelp.dll is always present on Windows.
 */
typedef BOOL (WINAPI *PFN_SymInitialize)(HANDLE, PCSTR, BOOL);
typedef BOOL (WINAPI *PFN_SymFromAddr)(HANDLE, DWORD64, PDWORD64, PSYMBOL_INFO);
typedef BOOL (WINAPI *PFN_SymGetLineFromAddr64)(HANDLE, DWORD64, PDWORD, PIMAGEHLP_LINE64);
typedef BOOL (WINAPI *PFN_SymCleanup)(HANDLE);

void ktc_stacktrace_print(const char* inMessage, int inMessageLen)
{
    fprintf(stderr, "Exception in thread \"main\" kotlin.Error: %.*s\n",
        inMessageLen, inMessage);

    void*  vFrames[KTC_ST_MAX_FRAMES];
    USHORT vFrameCount = CaptureStackBackTrace(
        KTC_ST_SKIP_FRAMES, KTC_ST_MAX_FRAMES, vFrames, NULL
    );

    HMODULE vDbgHelp = LoadLibraryA("dbghelp.dll");
    if (!vDbgHelp) {
        for (USHORT i = 0; i < vFrameCount; i++)
            fprintf(stderr, "\tat ??(Unknown Source)\n");
        return;
    }

    PFN_SymInitialize        vSymInit  = (PFN_SymInitialize)       GetProcAddress(vDbgHelp, "SymInitialize");
    PFN_SymFromAddr          vSymFrom  = (PFN_SymFromAddr)         GetProcAddress(vDbgHelp, "SymFromAddr");
    PFN_SymGetLineFromAddr64 vSymLine  = (PFN_SymGetLineFromAddr64)GetProcAddress(vDbgHelp, "SymGetLineFromAddr64");
    PFN_SymCleanup           vSymClean = (PFN_SymCleanup)          GetProcAddress(vDbgHelp, "SymCleanup");

    HANDLE vProcess = GetCurrentProcess();
    if (vSymInit) vSymInit(vProcess, NULL, TRUE);

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
        BOOL vHasSym  = vSymFrom && vSymFrom(vProcess, (DWORD64)vFrames[i], &vDisp64, vSym);
        BOOL vHasLine = vSymLine  && vSymLine(vProcess, (DWORD64)vFrames[i], &vDisp32, &vLine);

        const char* vFunc = (vHasSym && vSym->Name[0]) ? vSym->Name : "??";
        if (vHasLine)
            fprintf(stderr, "\tat %s(%s:%lu)\n", vFunc, ktc_st_basename(vLine.FileName), vLine.LineNumber);
        else
            fprintf(stderr, "\tat %s(Unknown Source)\n", vFunc);
    }

    if (vSymClean) vSymClean(vProcess);
    FreeLibrary(vDbgHelp);
}

#endif /* MinGW vs MSVC */

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