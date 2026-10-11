// Minimal stand-in for "GTA5.exe + an ASI loader": loads ThorBridgeV.asi
// from the current directory, keeps the process alive for N seconds
// (argv[1], default 15), then exits. Used by run_mock_test.py.

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <cstdlib>

int main(int argc, char** argv) {
    int seconds = argc > 1 ? std::atoi(argv[1]) : 15;
    HMODULE asi = LoadLibraryA("ThorBridgeV.asi");
    if (!asi) {
        std::fprintf(stderr, "[host] LoadLibrary(ThorBridgeV.asi) failed: %lu\n",
                     GetLastError());
        return 1;
    }
    std::fprintf(stderr, "[host] ThorBridgeV.asi loaded; running %d s\n", seconds);
    std::fflush(stderr);
    Sleep(static_cast<DWORD>(seconds) * 1000);
    return 0;
}
