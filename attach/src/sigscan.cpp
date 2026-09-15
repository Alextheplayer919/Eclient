// ─────────────────────────────────────────────────────────────────────────────
// Minimal self-contained ARM64 pattern scanner.
//
// Self-contained means: no LeviLaunchroid, no preloader runtime, no external
// SDK. Works by walking /proc/self/maps to find the loaded module's r-x
// (executable) segments and scanning them for a byte pattern with '?'
// wildcards — the standard technique every injected client uses (Horion,
// BedrockBaritone, BedrockTools…), here without any third-party runtime.
// ─────────────────────────────────────────────────────────────────────────────
#include "sigscan.h"

#include <cctype>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

namespace sigscan {

namespace {

struct SigByte {
    bool wildcard;
    unsigned char value;
};

std::vector<SigByte> parsePattern(std::string_view pattern) {
    std::vector<SigByte> out;
    const char* p = pattern.data();
    const char* end = p + pattern.size();
    while (p < end) {
        while (p < end && std::isspace(static_cast<unsigned char>(*p))) ++p;
        if (p >= end) break;
        if (*p == '?') {
            out.push_back({true, 0});
            ++p;
        } else {
            if (p + 1 >= end) { out.clear(); return out; }
            auto hex = [](char c) -> int {
                if (c >= '0' && c <= '9') return c - '0';
                if (c >= 'a' && c <= 'f') return 10 + (c - 'a');
                if (c >= 'A' && c <= 'F') return 10 + (c - 'A');
                return -1;
            };
            const int hi = hex(p[0]);
            const int lo = hex(p[1]);
            if (hi < 0 || lo < 0) { out.clear(); return out; }
            out.push_back({false, static_cast<unsigned char>((hi << 4) | lo)});
            p += 2;
        }
    }
    return out;
}

bool matchesAt(const unsigned char* mem, const std::vector<SigByte>& sig) {
    for (std::size_t i = 0; i < sig.size(); ++i) {
        if (!sig[i].wildcard && mem[i] != sig[i].value) return false;
    }
    return true;
}

void forEachExecSegment(const std::string& moduleName,
                        const std::function<void(const unsigned char*, std::size_t)>& fn) {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        // "<start>-<end> <perms> <off> <dev> <inode> <path>"
        if (line.find(moduleName) == std::string::npos) continue;
        std::istringstream iss(line);
        std::string range, perms;
        iss >> range >> perms;
        if (perms.size() < 3) continue;
        if (perms[0] != 'r' || perms[2] != 'x') continue; // code segments only
        const auto dash = range.find('-');
        if (dash == std::string::npos) continue;
        const uintptr_t begin = std::stoull(range.substr(0, dash), nullptr, 16);
        const uintptr_t stop  = std::stoull(range.substr(dash + 1), nullptr, 16);
        if (stop > begin) fn(reinterpret_cast<const unsigned char*>(begin), stop - begin);
    }
}

} // namespace

bool isModuleLoaded(std::string_view moduleName) {
    std::ifstream maps("/proc/self/maps");
    std::string line;
    while (std::getline(maps, line)) {
        if (line.find(moduleName) != std::string::npos) return true;
    }
    return false;
}

uintptr_t scan(std::string_view pattern, std::string_view moduleName) {
    const auto sig = parsePattern(pattern);
    if (sig.empty()) return 0;

    uintptr_t hit = 0;
    forEachExecSegment(std::string(moduleName), [&](const unsigned char* base, std::size_t size) {
        if (hit != 0 || size < sig.size()) return;
        const std::size_t last = size - sig.size();
        for (std::size_t i = 0; i <= last; ++i) {
            if (matchesAt(base + i, sig)) { hit = reinterpret_cast<uintptr_t>(base + i); return; }
        }
    });
    return hit;
}

} // namespace sigscan
