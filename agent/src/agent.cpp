// android-debugger JVMTI agent — v1.5 HotSwap.
//
// Loaded into a debuggable Android app via `cmd activity attach-agent`.
// Exposes a JSON-RPC server on a Unix abstract-namespace socket
// (@android-debugger-<package>) that the Kotlin MCP server connects to over
// `adb forward localfilesystem:<host> localabstract:<abstract>`.
//
// Design decisions (see .claude/plans/android-debugger-v1.4.md):
//   D6  — claim all JVMTI capabilities eagerly at attach
//   D8  — install signal handlers writing a crash-marker file
//   D9  — strict protocol-version handshake on every client connection
//   D11 — no socket auth in v1.4 (documented constraint)
//   D12 — fully stripped binary in release
//   D13 — first-in wins; second concurrent client refused with agent_in_use
//   D15 — quiet logging by default (attach/detach milestones + errors only)
//   D16 — addresses only in crash file (no libunwind)
//   D17 — options string is query-string: key=value,key2=value2
//
// Threading model:
//   - Agent_OnAttach runs on whatever thread `attach-agent` injects into.
//   - We spawn ONE listener pthread that serves at most one client at a time.
//   - JVMTI access in the listener thread requires JNI-attach (we do it inside
//     the listener's startup, then keep the attachment for the thread's life).
//
// v1.4 wire surface:
//   `hello` (protocol handshake), `ping`, `agent_info_raw`.
//
// v1.5 adds (protocol bump 1 → 2):
//   `agent.redefine_classes` — JVMTI RedefineClasses for HotSwap.
//   `agent.pop_frame`         — JVMTI PopFrame for force-re-enter.
//   `agent.get_original_class_bytes` — return ClassFileLoadHook-cached bytes.
//
// ClassFileLoadHook caching: registered eagerly in Agent_OnAttach so every
// post-attach class load is captured. Bytes go into g_class_bytes_cache keyed
// by JVM-internal signature (e.g. "Lcom/example/Foo;"). LRU-evicted at 5000
// entries — typical APK has 2-3 k classes, so the cap leaves room for plugin
// loaders. Classes loaded BEFORE the agent attached are never captured —
// documented limitation; revert returns class_bytes_not_cached for those.

#include <jni.h>
#include <jvmti.h>
#include <android/log.h>

#include <atomic>
#include <cctype>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/un.h>
#include <unistd.h>

// Build-time-injected (see CMakeLists.txt).
#ifndef AGENT_VERSION
#define AGENT_VERSION "0.0.0-dev"
#endif
#ifndef AGENT_PROTOCOL_VERSION
#define AGENT_PROTOCOL_VERSION 1
#endif

#define LOG_TAG "amdb_agent"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// JVMTI_CHECK: if the JVMTI call returns a non-NONE error code, log and abort.
// Per D8 — abort triggers SIGABRT, which the crash handler catches and writes
// the marker file. Most JVMTI failures shouldn't happen on a healthy ART; if
// they do, we want a loud diagnostic, not silent degradation.
#define JVMTI_CHECK(call)                                                      \
    do {                                                                       \
        jvmtiError __err = (call);                                             \
        if (__err != JVMTI_ERROR_NONE) {                                       \
            __android_log_assert(LOG_TAG, LOG_TAG,                             \
                "JVMTI call failed: %s = %d", #call, __err);                   \
        }                                                                      \
    } while (0)

// ---------------- file-scope state ----------------

namespace {

std::atomic<bool> g_initialized{false};
jvmtiEnv* g_jvmti = nullptr;
JavaVM* g_jvm = nullptr;

// Cached at attach. Avoids live JVMTI calls on every `ping`.
std::string g_capabilities_json;
std::string g_package_name;
int g_verbose = 0;
int64_t g_attached_at_ms = 0;

// Crash diagnostics — set early so signal handlers can use them.
// Must remain async-signal-safe (no malloc, no STL).
char g_crash_file_path[256] = {0};
char g_last_rpc_method[64] = {0};
std::atomic<const char*> g_last_rpc_method_ptr{nullptr};

// Single active client. Per D13 (first-in wins).
std::atomic<int> g_active_fd{-1};

pthread_t g_listener_thread = 0;
std::atomic<bool> g_should_stop{false};
int g_listen_fd = -1;

// v1.5: ClassFileLoadHook byte cache. Keyed by class signature in JVM internal
// form ("Lcom/example/Foo;"). Stores the raw bytes ART hands the agent at class
// load time — that's the "original" content the server can revert to after a
// HotSwap. Insertion order kept in g_class_bytes_order so we can drop oldest
// past the cap. All access guarded by g_class_bytes_mutex.
constexpr size_t kClassBytesCacheCap = 5000;
std::mutex g_class_bytes_mutex;
std::map<std::string, std::vector<uint8_t>> g_class_bytes_cache;
std::deque<std::string> g_class_bytes_order;  // FIFO of keys for eviction

// Forward declarations for v1.5 — handlers reference send_response/error_json
// which are defined later in the file.
int send_response(int fd, long long id, const std::string& result_or_error, bool is_error);
std::string error_json(int code, const char* message, const std::string& data = "");
std::string json_escape(const std::string& s);
std::string read_json_string(const std::string& s, size_t& pos);
long long read_json_int(const std::string& s, size_t& pos);
size_t find_json_key(const std::string& s, size_t obj_start, const char* key);

// ---------------- async-signal-safe helpers ----------------

// Write a NUL-terminated string fully or until EAGAIN. Async-signal-safe.
void as_safe_write(int fd, const char* s) {
    size_t len = strlen(s);
    while (len > 0) {
        ssize_t n = write(fd, s, len);
        if (n <= 0) {
            if (errno == EINTR) continue;
            break;
        }
        s += n;
        len -= n;
    }
}

// Convert a long to decimal in a stack buffer. Returns the number of bytes
// written (no NUL terminator). Async-signal-safe.
size_t as_safe_ltoa(long v, char* out, size_t cap) {
    if (cap == 0) return 0;
    if (v < 0) {
        if (cap < 2) return 0;
        *out++ = '-';
        cap--;
        v = -v;
    }
    char tmp[32];
    size_t n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0 && n < sizeof(tmp)) {
        tmp[n++] = '0' + static_cast<char>(v % 10);
        v /= 10;
    }
    if (n > cap) n = cap;
    size_t total = n;
    while (n--) *out++ = tmp[n];
    return total;
}

// Convert a uintptr_t to hex in a stack buffer. Returns bytes written.
size_t as_safe_ptohex(uintptr_t v, char* out, size_t cap) {
    if (cap < 3) return 0;
    *out++ = '0'; *out++ = 'x';
    size_t wrote = 2;
    char tmp[32];
    size_t n = 0;
    if (v == 0) tmp[n++] = '0';
    while (v > 0 && n < sizeof(tmp)) {
        int d = v & 0xf;
        tmp[n++] = (d < 10) ? ('0' + d) : ('a' + d - 10);
        v >>= 4;
    }
    while (n-- && wrote < cap) {
        *out++ = tmp[n];
        wrote++;
    }
    return wrote;
}

const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGFPE:  return "SIGFPE";
        default:      return "UNKNOWN";
    }
}

// ---------------- crash handler ----------------
//
// Per D8 — write a crash-marker file the Kotlin server can pick up on the next
// attach. Async-signal-safe: no malloc, no fprintf, no STL.
void crash_handler(int sig, siginfo_t* info, void* ctx_void) {
    int fd = open(g_crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (fd < 0) {
        // Re-raise with default disposition so the kernel produces the tombstone.
        signal(sig, SIG_DFL);
        raise(sig);
        return;
    }

    char buf[512];
    char* p = buf;
    char* end = buf + sizeof(buf);

    auto emit = [&](const char* s) {
        size_t l = strlen(s);
        if (p + l > end) l = end - p;
        memcpy(p, s, l);
        p += l;
    };
    auto emit_n = [&](size_t n) {
        if (p + n > end) n = end - p;
        p += n;
    };

    emit("signal="); emit(signal_name(sig)); emit("\n");
    emit("agent_version="); emit(AGENT_VERSION); emit("\n");

    // Faulting address.
    emit("si_addr=");
    emit_n(as_safe_ptohex(reinterpret_cast<uintptr_t>(info->si_addr), p, end - p));
    emit("\n");

    // PC + LR from ucontext. ARM/aarch64/x86_64 layouts differ; we only read PC
    // for the major archs we ship (arm64, x86_64, armv7).
    auto* ctx = static_cast<ucontext_t*>(ctx_void);
    uintptr_t pc = 0;
#if defined(__aarch64__)
    pc = ctx ? ctx->uc_mcontext.pc : 0;
#elif defined(__arm__)
    pc = ctx ? ctx->uc_mcontext.arm_pc : 0;
#elif defined(__x86_64__)
    pc = ctx ? ctx->uc_mcontext.gregs[REG_RIP] : 0;
#elif defined(__i386__)
    pc = ctx ? ctx->uc_mcontext.gregs[REG_EIP] : 0;
#endif
    emit("pc=");
    emit_n(as_safe_ptohex(pc, p, end - p));
    emit("\n");

    // PID + TID.
    emit("pid=");
    emit_n(as_safe_ltoa(getpid(), p, end - p));
    emit("\n");
    emit("tid=");
    emit_n(as_safe_ltoa(syscall(SYS_gettid), p, end - p));
    emit("\n");

    // Last RPC method seen (if any). The atomic load is signal-safe; the string
    // pointed to lives in a static buffer that doesn't churn.
    const char* last = g_last_rpc_method_ptr.load(std::memory_order_relaxed);
    if (last) {
        emit("last_rpc_method="); emit(last); emit("\n");
    }

    // Wall-clock time (best effort — clock_gettime is async-signal-safe per POSIX).
    struct timespec ts;
    if (clock_gettime(CLOCK_REALTIME, &ts) == 0) {
        emit("when_unix=");
        emit_n(as_safe_ltoa(static_cast<long>(ts.tv_sec), p, end - p));
        emit("\n");
    }

    if (p > buf) {
        as_safe_write(fd, "");  // no-op; flush via close
        ssize_t total = p - buf;
        while (total > 0) {
            ssize_t n = write(fd, buf + ((p - buf) - total), total);
            if (n <= 0) {
                if (errno == EINTR) continue;
                break;
            }
            total -= n;
        }
    }
    close(fd);

    // Re-raise so the kernel produces the tombstone as usual.
    signal(sig, SIG_DFL);
    raise(sig);
}

void install_crash_handlers() {
    struct sigaction sa{};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);

    int sigs[] = {SIGSEGV, SIGABRT, SIGBUS, SIGILL, SIGFPE};
    for (int s : sigs) {
        if (sigaction(s, &sa, nullptr) != 0) {
            LOGW("failed to install handler for %s: %s", signal_name(s), strerror(errno));
        }
    }
}

// ---------------- options parser ----------------
//
// Per D17 — `key=value,key2=value2`. Permissive: malformed pairs are skipped
// with a WARN. Recognized keys: `package`, `verbose`, `version`. Unknown keys
// also WARN-logged but not fatal.
std::map<std::string, std::string> parse_options(const char* options) {
    std::map<std::string, std::string> out;
    if (!options || !*options) return out;

    std::string s(options);
    size_t i = 0;
    while (i < s.size()) {
        size_t comma = s.find(',', i);
        if (comma == std::string::npos) comma = s.size();
        std::string pair = s.substr(i, comma - i);
        i = comma + 1;
        size_t eq = pair.find('=');
        if (eq == std::string::npos || eq == 0) {
            LOGW("malformed option pair: '%s'", pair.c_str());
            continue;
        }
        std::string key = pair.substr(0, eq);
        std::string val = pair.substr(eq + 1);
        out[key] = val;
    }
    return out;
}

// ---------------- capability probe ----------------

void claim_capabilities_eagerly() {
    jvmtiCapabilities potential{};
    JVMTI_CHECK(g_jvmti->GetPotentialCapabilities(&potential));
    JVMTI_CHECK(g_jvmti->AddCapabilities(&potential));
}

void build_capabilities_json() {
    jvmtiCapabilities caps{};
    JVMTI_CHECK(g_jvmti->GetCapabilities(&caps));

    // Hand-rolled JSON to avoid pulling in a JSON lib for one map.
    // Order doesn't matter to consumers; we emit a stable alphabetical-ish order.
    char buf[2048];
    int n = snprintf(buf, sizeof(buf),
        "{"
        "\"can_redefine_classes\":%s,"
        "\"can_retransform_classes\":%s,"
        "\"can_redefine_any_class\":%s,"
        "\"can_retransform_any_class\":%s,"
        "\"can_get_bytecodes\":%s,"
        "\"can_generate_breakpoint_events\":%s,"
        "\"can_generate_method_entry_events\":%s,"
        "\"can_generate_method_exit_events\":%s,"
        "\"can_generate_exception_events\":%s,"
        "\"can_generate_compiled_method_load_events\":%s,"
        "\"can_generate_single_step_events\":%s,"
        "\"can_generate_field_access_events\":%s,"
        "\"can_generate_field_modification_events\":%s,"
        "\"can_get_owned_monitor_info\":%s,"
        "\"can_get_current_contended_monitor\":%s,"
        "\"can_get_monitor_info\":%s,"
        "\"can_tag_objects\":%s,"
        "\"can_signal_thread\":%s,"
        "\"can_get_source_file_name\":%s,"
        "\"can_get_line_numbers\":%s,"
        "\"can_get_source_debug_extension\":%s,"
        "\"can_force_early_return\":%s,"
        "\"can_pop_frame\":%s,"
        "\"can_generate_garbage_collection_events\":%s,"
        "\"can_generate_object_free_events\":%s,"
        "\"can_generate_vm_object_alloc_events\":%s,"
        "\"can_access_local_variables\":%s,"
        "\"can_suspend\":%s,"
        "\"can_maintain_original_method_order\":%s"
        "}",
        caps.can_redefine_classes ? "true" : "false",
        caps.can_retransform_classes ? "true" : "false",
        caps.can_redefine_any_class ? "true" : "false",
        caps.can_retransform_any_class ? "true" : "false",
        caps.can_get_bytecodes ? "true" : "false",
        caps.can_generate_breakpoint_events ? "true" : "false",
        caps.can_generate_method_entry_events ? "true" : "false",
        caps.can_generate_method_exit_events ? "true" : "false",
        caps.can_generate_exception_events ? "true" : "false",
        caps.can_generate_compiled_method_load_events ? "true" : "false",
        caps.can_generate_single_step_events ? "true" : "false",
        caps.can_generate_field_access_events ? "true" : "false",
        caps.can_generate_field_modification_events ? "true" : "false",
        caps.can_get_owned_monitor_info ? "true" : "false",
        caps.can_get_current_contended_monitor ? "true" : "false",
        caps.can_get_monitor_info ? "true" : "false",
        caps.can_tag_objects ? "true" : "false",
        caps.can_signal_thread ? "true" : "false",
        caps.can_get_source_file_name ? "true" : "false",
        caps.can_get_line_numbers ? "true" : "false",
        caps.can_get_source_debug_extension ? "true" : "false",
        caps.can_force_early_return ? "true" : "false",
        caps.can_pop_frame ? "true" : "false",
        caps.can_generate_garbage_collection_events ? "true" : "false",
        caps.can_generate_object_free_events ? "true" : "false",
        caps.can_generate_vm_object_alloc_events ? "true" : "false",
        caps.can_access_local_variables ? "true" : "false",
        caps.can_suspend ? "true" : "false",
        caps.can_maintain_original_method_order ? "true" : "false"
    );
    if (n < 0 || n >= static_cast<int>(sizeof(buf))) {
        LOGE("capabilities JSON didn't fit in buffer (n=%d)", n);
        g_capabilities_json = "{}";
        return;
    }
    g_capabilities_json.assign(buf, static_cast<size_t>(n));
}

// ---------------- JSON-RPC helpers ----------------

// Minimal JSON-string escape — quotes, backslashes, newlines, tabs. Returns
// a newly-allocated std::string.
std::string json_escape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"':  out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            case '\b': out += "\\b"; break;
            case '\f': out += "\\f"; break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char tmp[8];
                    snprintf(tmp, sizeof(tmp), "\\u%04x", c);
                    out += tmp;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

// Read a JSON string value at `pos`, advancing past the closing quote.
// Returns "" and leaves `pos` unchanged on parse failure.
std::string read_json_string(const std::string& s, size_t& pos) {
    while (pos < s.size() && std::isspace(static_cast<unsigned char>(s[pos]))) pos++;
    if (pos >= s.size() || s[pos] != '"') return "";
    pos++;
    std::string out;
    while (pos < s.size() && s[pos] != '"') {
        if (s[pos] == '\\' && pos + 1 < s.size()) {
            char next = s[pos + 1];
            switch (next) {
                case '"':  out += '"';  break;
                case '\\': out += '\\'; break;
                case 'n':  out += '\n'; break;
                case 'r':  out += '\r'; break;
                case 't':  out += '\t'; break;
                default:   out += next; break;
            }
            pos += 2;
        } else {
            out += s[pos++];
        }
    }
    if (pos < s.size()) pos++;  // past closing quote
    return out;
}

// Read a JSON integer at `pos`. Returns 0 on failure.
long long read_json_int(const std::string& s, size_t& pos) {
    while (pos < s.size() && std::isspace(static_cast<unsigned char>(s[pos]))) pos++;
    char* end = nullptr;
    long long v = strtoll(s.c_str() + pos, &end, 10);
    if (end) pos = end - s.c_str();
    return v;
}

// Find a key in a JSON object string starting at `obj_start` (the opening `{`).
// Returns the position right after `"key":` and the colon-skipped whitespace,
// or std::string::npos on miss.
size_t find_json_key(const std::string& s, size_t obj_start, const char* key) {
    std::string needle = std::string("\"") + key + "\"";
    size_t end = std::string::npos;
    int depth = 0;
    for (size_t i = obj_start; i < s.size(); ++i) {
        char c = s[i];
        if (c == '{' || c == '[') depth++;
        else if (c == '}' || c == ']') depth--;
        if (depth == 1 && c == '"') {
            // Match the key at i if it equals "key".
            if (i + needle.size() <= s.size() && s.compare(i, needle.size(), needle) == 0) {
                // Make sure this is at object-depth 1 (top-level of this object).
                size_t j = i + needle.size();
                while (j < s.size() && std::isspace(static_cast<unsigned char>(s[j]))) j++;
                if (j < s.size() && s[j] == ':') {
                    j++;
                    while (j < s.size() && std::isspace(static_cast<unsigned char>(s[j]))) j++;
                    end = j;
                    break;
                }
            }
            // Skip past this string
            i++;
            while (i < s.size() && s[i] != '"') {
                if (s[i] == '\\' && i + 1 < s.size()) i++;
                i++;
            }
        }
    }
    return end;
}

// Find the value position immediately after `[` for a JSON array stored under
// `key` (relative to `obj_start`). Returns the index of the first element char
// (after `[`) or std::string::npos if the key is absent or not an array.
size_t find_json_array_start(const std::string& s, size_t obj_start, const char* key) {
    size_t pos = find_json_key(s, obj_start, key);
    if (pos == std::string::npos) return std::string::npos;
    if (pos >= s.size() || s[pos] != '[') return std::string::npos;
    return pos + 1;
}

// Iterate top-level elements of a JSON array starting at `arr_start` (first char
// after the opening `[`). For each element, calls [callback] with the element
// substring [el_start, el_end). The callback returns false to stop iteration
// early. Returns the position of the closing `]`, or std::string::npos on
// malformed input. Skips whitespace; handles nested {}, [], and quoted strings.
template <typename Fn>
size_t iterate_json_array(const std::string& s, size_t arr_start, Fn&& callback) {
    size_t i = arr_start;
    while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) i++;
    if (i < s.size() && s[i] == ']') return i;  // empty array

    while (i < s.size()) {
        // Skip leading whitespace.
        while (i < s.size() && std::isspace(static_cast<unsigned char>(s[i]))) i++;
        size_t el_start = i;
        int depth = 0;
        bool in_str = false;
        while (i < s.size()) {
            char c = s[i];
            if (in_str) {
                if (c == '\\' && i + 1 < s.size()) { i += 2; continue; }
                if (c == '"') in_str = false;
                i++;
                continue;
            }
            if (c == '"') { in_str = true; i++; continue; }
            if (c == '{' || c == '[') { depth++; i++; continue; }
            if (c == '}' || c == ']') {
                if (depth == 0) {
                    // End of element OR end of array.
                    if (c == ']') {
                        // Final element ended at i; consume.
                        if (!callback(el_start, i)) return i;
                        return i;
                    }
                    return std::string::npos;  // malformed
                }
                depth--;
                i++;
                continue;
            }
            if (c == ',' && depth == 0) {
                if (!callback(el_start, i)) return i;
                i++;
                el_start = std::string::npos;
                break;
            }
            i++;
        }
        if (i >= s.size()) return std::string::npos;
    }
    return std::string::npos;
}

// Compose an iso8601-style timestamp from a unix-epoch milliseconds value.
std::string iso8601(int64_t ms) {
    time_t s = ms / 1000;
    struct tm tm;
    gmtime_r(&s, &tm);
    char buf[32];
    snprintf(buf, sizeof(buf), "%04d-%02d-%02dT%02d:%02d:%02dZ",
        tm.tm_year + 1900, tm.tm_mon + 1, tm.tm_mday,
        tm.tm_hour, tm.tm_min, tm.tm_sec);
    return buf;
}

int64_t now_ms() {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

// ---------------- base64 ----------------
//
// Hand-rolled because we can't pull in a crypto library to a stripped agent.
// Standard alphabet, padding via '='. Decode is tolerant of whitespace.

const char kB64Alphabet[65] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

std::string base64_encode(const uint8_t* data, size_t len) {
    std::string out;
    out.reserve(((len + 2) / 3) * 4);
    for (size_t i = 0; i < len; i += 3) {
        uint32_t v = static_cast<uint32_t>(data[i]) << 16;
        if (i + 1 < len) v |= static_cast<uint32_t>(data[i + 1]) << 8;
        if (i + 2 < len) v |= static_cast<uint32_t>(data[i + 2]);
        out += kB64Alphabet[(v >> 18) & 0x3F];
        out += kB64Alphabet[(v >> 12) & 0x3F];
        out += (i + 1 < len) ? kB64Alphabet[(v >> 6) & 0x3F] : '=';
        out += (i + 2 < len) ? kB64Alphabet[v & 0x3F] : '=';
    }
    return out;
}

// Returns true on success. On parse error returns false and `out` content is
// undefined (callers discard). Tolerant of whitespace.
bool base64_decode(const std::string& in, std::vector<uint8_t>& out) {
    out.clear();
    out.reserve((in.size() / 4) * 3);
    int decoded[256];
    for (int i = 0; i < 256; ++i) decoded[i] = -1;
    for (int i = 0; i < 64; ++i) decoded[static_cast<unsigned char>(kB64Alphabet[i])] = i;

    uint32_t buf = 0;
    int bits = 0;
    for (char c : in) {
        if (c == '=') break;
        if (std::isspace(static_cast<unsigned char>(c))) continue;
        int v = decoded[static_cast<unsigned char>(c)];
        if (v < 0) return false;
        buf = (buf << 6) | static_cast<uint32_t>(v);
        bits += 6;
        if (bits >= 8) {
            bits -= 8;
            out.push_back(static_cast<uint8_t>((buf >> bits) & 0xFF));
        }
    }
    return true;
}

// ---------------- class-bytes cache helpers ----------------
//
// Push bytes for `class_signature` into the cache. If we already cache the
// signature, keep the FIRST set of bytes — that's the "original" content for
// revert. If the cache is at cap, drop the oldest entry.
void cache_class_bytes(const std::string& signature, const uint8_t* bytes, size_t len) {
    std::lock_guard<std::mutex> lock(g_class_bytes_mutex);
    if (g_class_bytes_cache.find(signature) != g_class_bytes_cache.end()) {
        // Already cached. The FIRST capture wins — that's the pre-attach
        // original. Later loads (e.g., from a different classloader) don't
        // overwrite.
        return;
    }
    if (g_class_bytes_cache.size() >= kClassBytesCacheCap) {
        // Evict oldest. The deque holds insertion order; pop the front entry.
        if (!g_class_bytes_order.empty()) {
            const std::string& oldKey = g_class_bytes_order.front();
            g_class_bytes_cache.erase(oldKey);
            g_class_bytes_order.pop_front();
        }
    }
    g_class_bytes_cache.emplace(signature, std::vector<uint8_t>(bytes, bytes + len));
    g_class_bytes_order.push_back(signature);
}

// Retrieve cached bytes. Returns true if found.
bool get_cached_class_bytes(const std::string& signature, std::vector<uint8_t>& out) {
    std::lock_guard<std::mutex> lock(g_class_bytes_mutex);
    auto it = g_class_bytes_cache.find(signature);
    if (it == g_class_bytes_cache.end()) return false;
    out = it->second;
    return true;
}

// Build the body of `agent_info_raw`'s result. Reused by `ping` as well —
// callers just wrap it in the JSON-RPC envelope.
std::string agent_info_raw_result() {
    std::string out;
    out.reserve(g_capabilities_json.size() + 256);
    out += "{\"version\":\"" AGENT_VERSION "\",";
    out += "\"protocol_version\":";
    char buf[16];
    snprintf(buf, sizeof(buf), "%d", AGENT_PROTOCOL_VERSION);
    out += buf;
    out += ",\"attach_pid\":";
    snprintf(buf, sizeof(buf), "%d", getpid());
    out += buf;
    out += ",\"package\":\"" + json_escape(g_package_name) + "\"";
    out += ",\"attached_at\":\"" + iso8601(g_attached_at_ms) + "\"";
    out += ",\"capabilities\":" + g_capabilities_json;
    out += "}";
    return out;
}

// ---------------- ClassFileLoadHook callback ----------------
//
// Fires for every class load AFTER the agent attaches. We're not transforming —
// `new_class_data_len` stays 0 / `new_class_data` stays nullptr. The hook is
// purely a capture point so we can later revert a redefined class to its
// "original" form (the bytes ART had when the class first loaded).
//
// Important: this runs on whatever thread is loading the class. Don't grab any
// lock other than g_class_bytes_mutex. Don't call into JNI or JVMTI from here.
void JNICALL on_class_file_load_hook(jvmtiEnv* /*env*/,
                                    JNIEnv* /*jni*/,
                                    jclass /*class_being_redefined*/,
                                    jobject /*loader*/,
                                    const char* name,            // internal form, "com/foo/Bar", no L/;
                                    jobject /*protection_domain*/,
                                    jint class_data_len,
                                    const unsigned char* class_data,
                                    jint* /*new_class_data_len*/,
                                    unsigned char** /*new_class_data*/) {
    if (!name || class_data_len <= 0 || !class_data) return;
    // Convert to signature form: "L<name>;". This matches what GetClassSignature
    // returns, so the server can address classes uniformly.
    std::string sig;
    sig.reserve(strlen(name) + 2);
    sig.push_back('L');
    sig.append(name);
    sig.push_back(';');
    cache_class_bytes(sig, class_data, static_cast<size_t>(class_data_len));
}

// ---------------- v1.5 RPC: agent.redefine_classes ----------------
//
// Request shape:
//   {"jsonrpc":"2.0","id":N,"method":"agent.redefine_classes",
//    "params":{"entries":[
//       {"class_signature":"Lcom/foo/Bar;","dex_bytes_b64":"..."},
//       ...
//    ]}}
//
// Response (success):
//   {"result":{"redefined":["Lcom/foo/Bar;",...]}}
// Response (failure):
//   {"error":{"code":<jvmti_err>,"message":"redefine_failed_jvmti",
//             "data":{"jvmti_error":<int>,"failing_class":"..."}}}

struct RedefineEntry {
    std::string class_signature;
    std::vector<uint8_t> dex_bytes;
};

// Find a loaded jclass by JVM-internal signature ("Lcom/foo/Bar;"). Returns
// nullptr if not loaded. Uses GetLoadedClasses + GetClassSignature to match —
// FindClass would pick the wrong class loader for app classes.
//
// On success, the returned jclass is a LOCAL ref the caller must manage
// (the loaded-class array is freed before return).
jclass find_loaded_class_by_signature(JNIEnv* jni, const std::string& signature) {
    jint count = 0;
    jclass* classes = nullptr;
    jvmtiError err = g_jvmti->GetLoadedClasses(&count, &classes);
    if (err != JVMTI_ERROR_NONE || !classes) return nullptr;

    jclass match = nullptr;
    for (jint i = 0; i < count; ++i) {
        char* sig_c = nullptr;
        char* generic_c = nullptr;
        if (g_jvmti->GetClassSignature(classes[i], &sig_c, &generic_c) == JVMTI_ERROR_NONE) {
            if (sig_c && signature == sig_c) {
                match = static_cast<jclass>(jni->NewLocalRef(classes[i]));
            }
            if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
            if (generic_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic_c));
        }
        if (match) {
            // Continue freeing the rest BEFORE breaking — we own the array.
            // Just keep looping; the work per iter is tiny.
        }
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return match;
}

// Find a jthread by name (Thread.getName()). Returns nullptr if not found.
// Caller is responsible for the returned ref.
jthread find_thread_by_name(JNIEnv* jni, const std::string& name) {
    jint count = 0;
    jthread* threads = nullptr;
    if (g_jvmti->GetAllThreads(&count, &threads) != JVMTI_ERROR_NONE || !threads) {
        return nullptr;
    }
    jthread match = nullptr;
    for (jint i = 0; i < count; ++i) {
        jvmtiThreadInfo info{};
        if (g_jvmti->GetThreadInfo(threads[i], &info) == JVMTI_ERROR_NONE) {
            if (info.name && name == info.name) {
                match = static_cast<jthread>(jni->NewLocalRef(threads[i]));
            }
            if (info.name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(info.name));
            if (info.thread_group) jni->DeleteLocalRef(info.thread_group);
            if (info.context_class_loader) jni->DeleteLocalRef(info.context_class_loader);
        }
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(threads));
    return match;
}

// Read params.entries from `line`, decoding base64 dex bytes. Returns true on
// success; on parse failure populates `err_msg` and returns false.
bool parse_redefine_entries(const std::string& line, std::vector<RedefineEntry>& entries,
                            std::string& err_msg) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        err_msg = "missing params";
        return false;
    }
    size_t arr_start = find_json_array_start(line, params_pos, "entries");
    if (arr_start == std::string::npos) {
        err_msg = "missing or malformed entries array";
        return false;
    }
    bool ok = true;
    iterate_json_array(line, arr_start, [&](size_t el_start, size_t el_end) -> bool {
        std::string entry_str = line.substr(el_start, el_end - el_start);
        size_t sig_pos = find_json_key(entry_str, 0, "class_signature");
        size_t dex_pos = find_json_key(entry_str, 0, "dex_bytes_b64");
        if (sig_pos == std::string::npos || dex_pos == std::string::npos) {
            err_msg = "entry missing class_signature or dex_bytes_b64";
            ok = false;
            return false;
        }
        size_t tmp1 = sig_pos;
        std::string sig = read_json_string(entry_str, tmp1);
        size_t tmp2 = dex_pos;
        std::string b64 = read_json_string(entry_str, tmp2);
        if (sig.empty() || b64.empty()) {
            err_msg = "entry has empty class_signature or dex_bytes_b64";
            ok = false;
            return false;
        }
        std::vector<uint8_t> dex_bytes;
        if (!base64_decode(b64, dex_bytes) || dex_bytes.empty()) {
            err_msg = "entry dex_bytes_b64 is not valid base64";
            ok = false;
            return false;
        }
        entries.push_back({sig, std::move(dex_bytes)});
        return true;
    });
    if (!ok) return false;
    if (entries.empty()) {
        err_msg = "entries array is empty";
        return false;
    }
    return true;
}

// Handle agent.redefine_classes. Always resolves jclass refs FIRST for every
// entry so we don't fire RedefineClasses with a partial array. ART's
// RedefineClasses is all-or-nothing at the JVMTI level — passing it count > 1
// means atomic redefinition or none.
std::string handle_redefine_classes(JNIEnv* jni, const std::string& line, bool& is_error) {
    std::vector<RedefineEntry> entries;
    std::string err;
    if (!parse_redefine_entries(line, entries, err)) {
        is_error = true;
        return error_json(-32602, ("invalid_params: " + err).c_str());
    }

    // Resolve jclass for each entry.
    std::vector<jclass> jclasses;
    jclasses.reserve(entries.size());
    auto cleanup_jclasses = [&]() {
        for (jclass jc : jclasses) {
            if (jc) jni->DeleteLocalRef(jc);
        }
    };
    for (const auto& e : entries) {
        jclass jc = find_loaded_class_by_signature(jni, e.class_signature);
        if (!jc) {
            cleanup_jclasses();
            is_error = true;
            std::string data = "{\"failing_class\":\"" + json_escape(e.class_signature) + "\"}";
            return error_json(-32004, "class_not_loaded", data);
        }
        jclasses.push_back(jc);
    }

    // Build the jvmtiClassDefinition array.
    std::vector<jvmtiClassDefinition> defs(entries.size());
    for (size_t i = 0; i < entries.size(); ++i) {
        defs[i].klass = jclasses[i];
        defs[i].class_byte_count = static_cast<jint>(entries[i].dex_bytes.size());
        defs[i].class_bytes = entries[i].dex_bytes.data();
    }

    jvmtiError result = g_jvmti->RedefineClasses(
        static_cast<jint>(defs.size()), defs.data());
    cleanup_jclasses();

    if (result != JVMTI_ERROR_NONE) {
        is_error = true;
        // Surface which class failed when ART tells us.
        std::string failing_sig = entries.empty() ? "" : entries.front().class_signature;
        std::string data = "{\"jvmti_error\":";
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(result));
        data += buf;
        if (!failing_sig.empty()) {
            data += ",\"failing_class\":\"" + json_escape(failing_sig) + "\"";
        }
        data += "}";
        return error_json(-32005, "redefine_failed_jvmti", data);
    }

    // Success: list the redefined class signatures.
    std::string out = "{\"redefined\":[";
    for (size_t i = 0; i < entries.size(); ++i) {
        if (i > 0) out += ",";
        out += "\"" + json_escape(entries[i].class_signature) + "\"";
    }
    out += "]}";
    is_error = false;
    return out;
}

// ---------------- v1.5 RPC: agent.pop_frame ----------------
//
// Request:
//   {"params":{"thread_name":"main","frames_to_pop":1}}
// Returns the count actually popped (always equals frames_to_pop on success).
//
// The thread MUST be suspended for PopFrame to succeed. We use ThreadName as
// the addressing scheme because JDI's uniqueID isn't trivially mappable to
// JVMTI's jthread; name is stable for the lifetime of the thread.

std::string handle_pop_frame(JNIEnv* jni, const std::string& line, bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t name_pos = find_json_key(line, params_pos, "thread_name");
    if (name_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing thread_name");
    }
    size_t tmp = name_pos;
    std::string thread_name = read_json_string(line, tmp);
    if (thread_name.empty()) {
        is_error = true;
        return error_json(-32602, "invalid_params: empty thread_name");
    }

    long long frames_to_pop = 1;
    size_t ftp_pos = find_json_key(line, params_pos, "frames_to_pop");
    if (ftp_pos != std::string::npos) {
        size_t t2 = ftp_pos;
        frames_to_pop = read_json_int(line, t2);
        if (frames_to_pop < 1) frames_to_pop = 1;
    }

    jthread t = find_thread_by_name(jni, thread_name);
    if (!t) {
        is_error = true;
        return error_json(-32006, "thread_not_found",
            "{\"thread_name\":\"" + json_escape(thread_name) + "\"}");
    }

    int popped = 0;
    jvmtiError last_err = JVMTI_ERROR_NONE;
    for (long long i = 0; i < frames_to_pop; ++i) {
        jvmtiError pe = g_jvmti->PopFrame(t);
        if (pe != JVMTI_ERROR_NONE) {
            last_err = pe;
            break;
        }
        popped++;
    }
    jni->DeleteLocalRef(t);

    if (popped == 0) {
        is_error = true;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(last_err));
        std::string data = "{\"jvmti_error\":" + std::string(buf) + "}";
        const char* code_str = "pop_frame_failed";
        if (last_err == JVMTI_ERROR_OPAQUE_FRAME) {
            code_str = "opaque_frame";  // native frame, can't pop
        } else if (last_err == JVMTI_ERROR_THREAD_NOT_SUSPENDED) {
            code_str = "thread_not_suspended";
        } else if (last_err == JVMTI_ERROR_NO_MORE_FRAMES) {
            code_str = "no_more_frames";
        } else if (last_err == JVMTI_ERROR_MUST_POSSESS_CAPABILITY) {
            code_str = "capability_unavailable";
        }
        return error_json(-32007, code_str, data);
    }

    char buf[32];
    snprintf(buf, sizeof(buf), "%d", popped);
    std::string out = "{\"popped\":" + std::string(buf) + ",\"thread_name\":\"" +
        json_escape(thread_name) + "\"}";
    is_error = false;
    return out;
}

// ---------------- v1.5 RPC: agent.get_original_class_bytes ----------------
//
// Returns the cached pre-attach class bytes for a given signature. The server
// uses this as the source for hot_swap_revert. Bytes are JVM `.class` format
// (NOT dex) — the cache captures what ART hands the ClassFileLoadHook at load
// time, which is the original .class form.

std::string handle_get_original_class_bytes(const std::string& line, bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t sig_pos = find_json_key(line, params_pos, "class_signature");
    if (sig_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing class_signature");
    }
    size_t tmp = sig_pos;
    std::string signature = read_json_string(line, tmp);
    if (signature.empty()) {
        is_error = true;
        return error_json(-32602, "invalid_params: empty class_signature");
    }

    std::vector<uint8_t> bytes;
    if (!get_cached_class_bytes(signature, bytes)) {
        is_error = true;
        return error_json(-32008, "class_bytes_not_cached",
            "{\"class_signature\":\"" + json_escape(signature) +
            "\",\"hint\":\"class was loaded before agent attached or evicted from cache\"}");
    }

    std::string b64 = base64_encode(bytes.data(), bytes.size());
    char szbuf[32];
    snprintf(szbuf, sizeof(szbuf), "%zu", bytes.size());
    std::string out = "{\"class_signature\":\"" + json_escape(signature) +
        "\",\"class_bytes_b64\":\"" + b64 + "\",\"byte_count\":" + szbuf + "}";
    is_error = false;
    return out;
}

// Send a JSON-RPC response line (`{...}\n`) over `fd`. Returns 0 on success,
// -1 on write failure.
int send_response(int fd, long long id, const std::string& result_or_error,
                  bool is_error) {
    std::string line = "{\"jsonrpc\":\"2.0\",\"id\":";
    char buf[32];
    snprintf(buf, sizeof(buf), "%lld", id);
    line += buf;
    line += is_error ? ",\"error\":" : ",\"result\":";
    line += result_or_error;
    line += "}\n";
    size_t remaining = line.size();
    const char* p = line.data();
    while (remaining > 0) {
        ssize_t n = write(fd, p, remaining);
        if (n <= 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        p += n;
        remaining -= n;
    }
    return 0;
}

std::string error_json(int code, const char* message, const std::string& data) {
    char buf[32];
    snprintf(buf, sizeof(buf), "%d", code);
    std::string out = "{\"code\":";
    out += buf;
    out += ",\"message\":\"";
    out += json_escape(message);
    out += "\"";
    if (!data.empty()) {
        out += ",\"data\":" + data;
    }
    out += "}";
    return out;
}

// ---------------- listener thread ----------------

// Read one line (terminated by '\n') from `fd` into `out`. Returns true if a
// line was read, false on EOF or error.
bool read_line(int fd, std::string& out) {
    out.clear();
    char c;
    while (true) {
        ssize_t n = read(fd, &c, 1);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            return false;
        }
        if (c == '\n') return true;
        out += c;
        if (out.size() > 64 * 1024) {
            // Defensive cap: a single request shouldn't exceed 64 KB in v1.4.
            LOGE("request exceeds 64 KB cap; closing socket");
            return false;
        }
    }
}

// Serve a single accepted client. Enforces strict protocol-version handshake on
// the FIRST message. After handshake, dispatches the v1.4 read-only RPCs and
// the v1.5 HotSwap RPCs. Returns when the client disconnects.
//
// `jni` is the listener thread's attached JNIEnv*; needed for the v1.5 calls
// that resolve jclass refs via GetLoadedClasses + NewLocalRef.
void serve_client(int fd, JNIEnv* jni) {
    std::string line;
    bool handshake_done = false;

    while (read_line(fd, line)) {
        // Parse minimally: extract `id` (int) and `method` (string).
        long long id = 0;
        {
            size_t kpos = find_json_key(line, 0, "id");
            if (kpos != std::string::npos) {
                size_t tmp = kpos;
                id = read_json_int(line, tmp);
            }
        }
        std::string method;
        {
            size_t kpos = find_json_key(line, 0, "method");
            if (kpos != std::string::npos) {
                size_t tmp = kpos;
                method = read_json_string(line, tmp);
            }
        }
        if (method.empty()) {
            send_response(fd, id, error_json(-32600, "method missing"), true);
            continue;
        }

        // Track for crash diagnostics. Copy into the static buffer atomically.
        // Truncate if longer than buffer.
        size_t mn = method.size();
        if (mn >= sizeof(g_last_rpc_method)) mn = sizeof(g_last_rpc_method) - 1;
        memcpy(g_last_rpc_method, method.c_str(), mn);
        g_last_rpc_method[mn] = 0;
        g_last_rpc_method_ptr.store(g_last_rpc_method, std::memory_order_relaxed);

        if (!handshake_done) {
            // Per D9 — first message MUST be hello with protocol_version: 1.
            if (method != "hello") {
                send_response(fd, id,
                    error_json(-32099, "agent_version_mismatch",
                        "{\"hint\":\"first message must be hello with protocol_version=" +
                        std::to_string(AGENT_PROTOCOL_VERSION) + "\"}"),
                    true);
                return;
            }
            // Read params.protocol_version.
            long long client_pv = 0;
            size_t params_pos = find_json_key(line, 0, "params");
            if (params_pos != std::string::npos) {
                size_t pv_pos = find_json_key(line, params_pos, "protocol_version");
                if (pv_pos != std::string::npos) {
                    size_t tmp = pv_pos;
                    client_pv = read_json_int(line, tmp);
                }
            }
            if (client_pv != AGENT_PROTOCOL_VERSION) {
                std::string data = "{\"agent_protocol_version\":";
                data += std::to_string(AGENT_PROTOCOL_VERSION);
                data += ",\"client_protocol_version\":";
                data += std::to_string(client_pv);
                data += "}";
                send_response(fd, id,
                    error_json(-32099, "agent_version_mismatch", data), true);
                return;
            }
            // Success.
            std::string result = "{\"protocol_version\":";
            result += std::to_string(AGENT_PROTOCOL_VERSION);
            result += ",\"agent_version\":\"" AGENT_VERSION "\"}";
            send_response(fd, id, result, false);
            handshake_done = true;
            continue;
        }

        if (method == "ping") {
            send_response(fd, id, "{\"pong\":true}", false);
        } else if (method == "agent_info_raw") {
            send_response(fd, id, agent_info_raw_result(), false);
        } else if (method == "agent.redefine_classes") {
            bool is_err = false;
            std::string body = handle_redefine_classes(jni, line, is_err);
            send_response(fd, id, body, is_err);
            // Local refs created during the call are freed inside the handler; clear
            // the frame to be safe in case of leaks (cheap on Android JNI).
            jni->PushLocalFrame(0);
            jni->PopLocalFrame(nullptr);
        } else if (method == "agent.pop_frame") {
            bool is_err = false;
            std::string body = handle_pop_frame(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.get_original_class_bytes") {
            bool is_err = false;
            std::string body = handle_get_original_class_bytes(line, is_err);
            send_response(fd, id, body, is_err);
        } else {
            send_response(fd, id, error_json(-32601, "method not found"), true);
        }
    }
}

void* listener_main(void* /*arg*/) {
    // v1.5: attach the listener thread to the JVM so we can hold JNIEnv* across
    // calls (needed for FindClass / GetLoadedClasses / NewLocalRef during
    // RedefineClasses). DetachCurrentThread fires when the listener exits, so
    // the thread is properly cleaned up on agent shutdown.
    JNIEnv* jni = nullptr;
    if (g_jvm) {
        JavaVMAttachArgs args{};
        args.version = JNI_VERSION_1_6;
        args.name = const_cast<char*>("amdb_agent_listener");
        args.group = nullptr;
        if (g_jvm->AttachCurrentThread(&jni, &args) != JNI_OK) {
            LOGE("listener: AttachCurrentThread failed; v1.5 RPCs will be unavailable");
            jni = nullptr;
        }
    }

    // Build the abstract socket name: @android-debugger-<package>.
    std::string sock_name = "android-debugger-" + g_package_name;
    if (sock_name.size() > sizeof(sockaddr_un::sun_path) - 1) {
        LOGE("abstract socket name too long (%zu); truncating", sock_name.size());
        sock_name.resize(sizeof(sockaddr_un::sun_path) - 1);
    }

    g_listen_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_listen_fd < 0) {
        LOGE("socket() failed: %s", strerror(errno));
        return nullptr;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    // Abstract namespace: sun_path[0] == '\0', followed by the name.
    addr.sun_path[0] = '\0';
    memcpy(addr.sun_path + 1, sock_name.data(), sock_name.size());
    socklen_t addr_len = offsetof(sockaddr_un, sun_path) + 1 + sock_name.size();

    if (bind(g_listen_fd, reinterpret_cast<sockaddr*>(&addr), addr_len) != 0) {
        LOGE("bind(@%s) failed: %s", sock_name.c_str(), strerror(errno));
        close(g_listen_fd);
        g_listen_fd = -1;
        return nullptr;
    }
    if (listen(g_listen_fd, 4) != 0) {
        LOGE("listen() failed: %s", strerror(errno));
        close(g_listen_fd);
        g_listen_fd = -1;
        return nullptr;
    }

    LOGI("listener bound to @%s", sock_name.c_str());

    while (!g_should_stop.load(std::memory_order_relaxed)) {
        // poll with timeout so we can check g_should_stop periodically.
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(g_listen_fd, &rfds);
        timeval tv{.tv_sec = 0, .tv_usec = 200 * 1000};
        int sret = select(g_listen_fd + 1, &rfds, nullptr, nullptr, &tv);
        if (sret <= 0) continue;

        int client = accept(g_listen_fd, nullptr, nullptr);
        if (client < 0) {
            if (errno == EINTR) continue;
            LOGW("accept() failed: %s", strerror(errno));
            continue;
        }

        // Per D13 — first-in wins.
        int expected = -1;
        if (!g_active_fd.compare_exchange_strong(expected, client)) {
            // Already serving another client — refuse this one with a JSON-RPC error.
            const char* refusal =
                "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":"
                "{\"code\":-32099,\"message\":\"agent_in_use\","
                "\"data\":{\"hint\":\"another debugger session is already attached; "
                "the existing session must detach first\"}}}\n";
            write(client, refusal, strlen(refusal));
            close(client);
            continue;
        }

        if (g_verbose) LOGI("client connected (fd=%d)", client);
        serve_client(client, jni);
        if (g_verbose) LOGI("client disconnected");
        close(client);
        g_active_fd.store(-1, std::memory_order_relaxed);
    }

    if (g_listen_fd >= 0) {
        close(g_listen_fd);
        g_listen_fd = -1;
    }
    if (jni && g_jvm) {
        g_jvm->DetachCurrentThread();
    }
    return nullptr;
}

}  // namespace

// ---------------- JNI/JVMTI entry points ----------------

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL Agent_OnAttach(JavaVM* vm, char* options, void* /*reserved*/) {
    // Re-attach within the same process is a no-op. JVMTI doesn't support
    // unload; the existing agent instance keeps serving.
    bool first_time = !g_initialized.exchange(true, std::memory_order_acq_rel);
    if (!first_time) {
        LOGI("re-attach detected (pid=%d) — agent already loaded; no-op", getpid());
        return JNI_OK;
    }

    g_jvm = vm;

    auto opts = parse_options(options);
    auto pkg_it = opts.find("package");
    if (pkg_it == opts.end() || pkg_it->second.empty()) {
        LOGE("Agent_OnAttach: missing required `package` option");
        return JNI_ERR;
    }
    g_package_name = pkg_it->second;
    auto verbose_it = opts.find("verbose");
    if (verbose_it != opts.end()) {
        g_verbose = std::atoi(verbose_it->second.c_str());
    }
    auto version_it = opts.find("version");
    if (version_it != opts.end()) {
        int client_pv = std::atoi(version_it->second.c_str());
        if (client_pv != AGENT_PROTOCOL_VERSION) {
            LOGE("Agent_OnAttach: protocol version mismatch (client=%d, agent=%d)",
                client_pv, AGENT_PROTOCOL_VERSION);
            return JNI_ERR;
        }
    }

    // Resolve the crash-file path now (we know the package).
    snprintf(g_crash_file_path, sizeof(g_crash_file_path),
        "/data/data/%s/cache/amdb_agent_crash.txt", g_package_name.c_str());

    // Install crash handlers as early as possible — any JVMTI/JNI call below
    // could segfault on a quirky ART version.
    install_crash_handlers();

    // Claim the JVMTI environment.
    jvmtiEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2) != JNI_OK) {
        LOGE("Agent_OnAttach: GetEnv(JVMTI_VERSION_1_2) failed — ART may not "
             "support JVMTI on this device");
        return JNI_ERR;
    }
    g_jvmti = env;

    claim_capabilities_eagerly();
    build_capabilities_json();

    // v1.5: register ClassFileLoadHook so every post-attach class load lands in
    // the bytes cache. The hook is non-transforming (we leave new_class_data_*
    // alone). Failure here isn't fatal — it just means revert can't return
    // original bytes for classes loaded post-attach; the server surfaces
    // class_bytes_not_cached and the agent loses revert for that class.
    {
        jvmtiEventCallbacks callbacks{};
        callbacks.ClassFileLoadHook = on_class_file_load_hook;
        jvmtiError ec_err = g_jvmti->SetEventCallbacks(&callbacks, sizeof(callbacks));
        if (ec_err != JVMTI_ERROR_NONE) {
            LOGE("SetEventCallbacks failed: %d — revert support disabled", ec_err);
        } else {
            jvmtiError en_err = g_jvmti->SetEventNotificationMode(
                JVMTI_ENABLE, JVMTI_EVENT_CLASS_FILE_LOAD_HOOK, nullptr);
            if (en_err != JVMTI_ERROR_NONE) {
                LOGE("SetEventNotificationMode(CLASS_FILE_LOAD_HOOK) failed: %d — "
                     "revert support disabled", en_err);
            } else if (g_verbose) {
                LOGI("ClassFileLoadHook armed; class bytes will be cached (cap=%zu)",
                    kClassBytesCacheCap);
            }
        }
    }

    g_attached_at_ms = now_ms();

    // Spawn the listener thread.
    g_should_stop.store(false, std::memory_order_relaxed);
    int err = pthread_create(&g_listener_thread, nullptr, listener_main, nullptr);
    if (err != 0) {
        LOGE("pthread_create failed: %s", strerror(err));
        return JNI_ERR;
    }

    LOGI("attached v" AGENT_VERSION " pid=%d package=%s",
        getpid(), g_package_name.c_str());
    return JNI_OK;
}

JNIEXPORT void JNICALL Agent_OnUnload(JavaVM* /*vm*/) {
    // JVMTI doesn't support agent unload in practice — but if the host ever
    // does call us, stop the listener cleanly.
    g_should_stop.store(true, std::memory_order_relaxed);
    if (g_listener_thread) {
        pthread_join(g_listener_thread, nullptr);
        g_listener_thread = 0;
    }
    LOGI("agent unloaded");
}

}  // extern "C"
