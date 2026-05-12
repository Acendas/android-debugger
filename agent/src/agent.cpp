// android-debugger JVMTI agent — v1.6 deep JVMTI surface.
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
// v1.6 adds (protocol bump 2 → 3):
//   `agent.heap_count_instances`     — JVMTI IterateThroughHeap (count + size).
//   `agent.heap_iterate_by_class`    — paginated materialization with vobj#<id> refs.
//   `agent.heap_find_referrers`      — FollowReferences reverse-edges, 1-hop.
//   `agent.heap_find_referrer_chain` — reverse BFS, depth-bounded, to GC roots.
//   `agent.method_trace_{start,read,stop,list}` — MethodEntry/Exit ring buffers.
//   `agent.alloc_trace_{start,read,stop,list}`  — VMObjectAlloc ring buffers.
//   `agent.stop_all_traces`          — cleanup on detach/disconnect.
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

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <regex>
#include <set>
#include <sstream>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
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

// Shared event-callbacks struct. SetEventCallbacks overwrites the *entire*
// struct, so every callback we want active must be re-set together. v1.5
// installed only ClassFileLoadHook; v1.6 adds MethodEntry / MethodExit /
// VMObjectAlloc on demand. The mutex serializes re-installs.
std::mutex g_event_cb_mutex;
jvmtiEventCallbacks g_event_callbacks{};

// ---------------- v1.6 file-scope state ----------------

// Ref-id minting. Refs returned to the server as "vobj#<id>".
std::atomic<int> g_v16_next_ref_id{1};
// Per-call op tag generator. Each heap-walk uses a fresh tag pair so concurrent
// or back-to-back walks don't see each other's tags.
std::atomic<jlong> g_v16_next_tag{1};
std::mutex g_v16_refs_mutex;
std::map<int, jobject> g_v16_refs;  // ref-id -> globalRef

// jmethodID -> (class_sig, method_name). Cached because both names require
// JVMTI calls + Deallocate, and method-trace callbacks fire at hot rate.
std::mutex g_method_name_cache_mutex;
std::map<jmethodID, std::pair<std::string, std::string>> g_method_name_cache;

// jmethodID -> (full_jvm_signature, return_type_char). Populated lazily by
// resolve_method_signature_cached() on the first method-exit event that needs
// to render a typed return value. Separate cache from g_method_name_cache so
// the hot-path entry callback that only wants names doesn't pay the full
// signature parse cost; method-exit pays it once per method then hits the
// cache.
std::mutex g_method_signature_cache_mutex;
std::map<jmethodID, std::pair<std::string, char>> g_method_signature_cache;

// Forward struct declarations (defined further below).
struct MethodEvent;
struct AllocEvent;
struct MethodTraceSession;
struct AllocTraceSession;

// Trace-session registries.
std::mutex g_method_traces_mutex;
std::map<std::string, std::shared_ptr<MethodTraceSession>> g_method_traces;
std::mutex g_alloc_traces_mutex;
std::map<std::string, std::shared_ptr<AllocTraceSession>> g_alloc_traces;

// Per-event subscriber counters. Drop to zero -> SetEventNotificationMode
// disables the event. Rise from zero -> re-enables. Lazy enable means we
// don't pay event-dispatch cost when no client cares.
std::atomic<int> g_method_entry_subscribers{0};
std::atomic<int> g_method_exit_subscribers{0};
std::atomic<int> g_alloc_subscribers{0};

// Per-thread call-depth + per-session sampled-stack tracking. The sampled
// stack mirrors entries/exits: on entry, push 1 if sampled / 0 if not; on
// exit, pop and only emit if the top was 1. Keyed by Linux tid (gettid()).
struct PerThreadState {
    int depth = 0;
    // session-id -> stack of bytes ('1' sampled, '0' not).
    std::map<std::string, std::vector<char>> sampled_stack;
    // session-id -> stack of nano timestamps captured at entry, so the
    // matching exit can compute elapsed_ns without scanning the buffer.
    std::map<std::string, std::vector<long long>> entry_nano_stack;
};
std::mutex g_per_thread_mutex;
// Owning map keyed by tid; we never let entries cross threads.
std::map<pid_t, std::unique_ptr<PerThreadState>> g_per_thread_states;

// Forward declarations.
int send_response(int fd, long long id, const std::string& result_or_error, bool is_error);
std::string error_json(int code, const char* message, const std::string& data = "");
std::string json_escape(const std::string& s);
std::string read_json_string(const std::string& s, size_t& pos);
long long read_json_int(const std::string& s, size_t& pos);
size_t find_json_key(const std::string& s, size_t obj_start, const char* key);

// v1.6 helper forward decls. The dispatch table in serve_client references the
// handlers; the handlers in turn reach for these utilities.
std::string mint_buffer_id(const char* prefix);
std::string resolve_class_sig_for_jclass(JNIEnv* jni, jclass klass);
std::pair<std::string, std::string> resolve_method_name_cached(jmethodID method);
// (full_jvm_signature, return_type_char) for the method. ret_type_char is 'V'
// for void, 'L'/'['/primitive char otherwise. Returns empty signature + '\0'
// if JVMTI lookup fails.
std::pair<std::string, char> resolve_method_signature_cached(jmethodID method);
// Render an arg JSON object given a name/slot/type-signature, reading the
// local at `slot` on the *current* (top) frame of `thread`. Returns empty
// string if the local can't be read; the caller decides whether to skip or
// fall back. `type_sig` is the JVMTI signature ("I", "Ljava/lang/String;",
// "[I", etc.).
std::string render_local_arg(JNIEnv* jni, jthread thread, jint slot,
                             const std::string& name, const std::string& type_sig);
// Capture entry-time args for the current frame on `thread` invoking
// `method`. Returns (args_json, args_absent). args_json is always a complete
// JSON array literal ("[]" if no args). args_absent is true when the method
// has no LocalVariableTable (release/R8 stripped build) — the caller emits
// the boolean alongside.
std::pair<std::string, bool> capture_method_entry_args(JNIEnv* jni,
                                                       jthread thread,
                                                       jmethodID method);
// Render a return value as a JSON value (NOT wrapped in any field). For void
// methods, returns "null"; the caller signals the void-ness via a separate
// boolean on the event. `ret_type_char` and `full_sig` come from
// resolve_method_signature_cached().
std::string render_return_value(JNIEnv* jni, char ret_type_char,
                                const std::string& full_sig,
                                const jvalue& v);
std::string resolve_thread_name(jthread thread);
long long now_nano();
int mint_v16_ref(JNIEnv* jni, jobject local_or_global);
bool resolve_v16_ref(int ref_id, jobject& out);
void release_v16_refs(JNIEnv* jni);
PerThreadState* get_or_create_per_thread();
bool refresh_event_callbacks();
void on_method_entry(jvmtiEnv* env, JNIEnv* jni, jthread thread, jmethodID method);
void on_method_exit(jvmtiEnv* env, JNIEnv* jni, jthread thread, jmethodID method,
                    jboolean was_popped_by_exception, jvalue return_value);
void on_vm_object_alloc(jvmtiEnv* env, JNIEnv* jni, jthread thread, jobject object,
                        jclass object_klass, jlong size);

// Internal trace-stop hook used by stop_all_traces and the disconnect path. Not
// an RPC handler; returns the number of method/alloc sessions reaped.
std::pair<int, int> stop_all_traces_internal();

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

// ---------------- v1.6 helpers ----------------

long long now_nano() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<long long>(ts.tv_sec) * 1000000000LL +
        static_cast<long long>(ts.tv_nsec);
}

// Generate a buffer-id like "mt-7c91a3d8f0e21b46". Random bytes from the
// kernel; falls back to time-seeded rand_r if /dev/urandom is unavailable.
std::string mint_buffer_id(const char* prefix) {
    unsigned char raw[8] = {0};
    bool got_random = false;
    int fd = open("/dev/urandom", O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        ssize_t n = read(fd, raw, sizeof(raw));
        close(fd);
        got_random = (n == static_cast<ssize_t>(sizeof(raw)));
    }
    if (!got_random) {
        unsigned int seed = static_cast<unsigned int>(now_nano() & 0xFFFFFFFF) ^
            static_cast<unsigned int>(getpid());
        for (size_t i = 0; i < sizeof(raw); ++i) {
            raw[i] = static_cast<unsigned char>(rand_r(&seed) & 0xFF);
        }
    }
    char buf[32];
    snprintf(buf, sizeof(buf), "%s-%02x%02x%02x%02x%02x%02x%02x%02x",
        prefix, raw[0], raw[1], raw[2], raw[3], raw[4], raw[5], raw[6], raw[7]);
    return std::string(buf);
}

// Resolve a class signature for a jclass, dropping the generic-signature output.
std::string resolve_class_sig_for_jclass(JNIEnv* /*jni*/, jclass klass) {
    if (!klass) return std::string();
    char* sig_c = nullptr;
    char* generic_c = nullptr;
    if (g_jvmti->GetClassSignature(klass, &sig_c, &generic_c) != JVMTI_ERROR_NONE) {
        return std::string();
    }
    std::string out = sig_c ? std::string(sig_c) : std::string();
    if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
    if (generic_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic_c));
    return out;
}

// Look up (class_signature, method_name) for a jmethodID; cache for hot paths.
std::pair<std::string, std::string> resolve_method_name_cached(jmethodID method) {
    if (!method) return {std::string(), std::string()};
    {
        std::lock_guard<std::mutex> lock(g_method_name_cache_mutex);
        auto it = g_method_name_cache.find(method);
        if (it != g_method_name_cache.end()) return it->second;
    }
    // Resolve outside the lock — Get* calls can be slow.
    char* name_c = nullptr;
    char* sig_c = nullptr;
    char* generic_c = nullptr;
    if (g_jvmti->GetMethodName(method, &name_c, &sig_c, &generic_c) != JVMTI_ERROR_NONE) {
        return {std::string(), std::string()};
    }
    std::string method_name = name_c ? std::string(name_c) : std::string();
    if (name_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name_c));
    if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
    if (generic_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic_c));

    jclass declaring = nullptr;
    std::string class_sig;
    if (g_jvmti->GetMethodDeclaringClass(method, &declaring) == JVMTI_ERROR_NONE && declaring) {
        char* csig = nullptr;
        char* cgen = nullptr;
        if (g_jvmti->GetClassSignature(declaring, &csig, &cgen) == JVMTI_ERROR_NONE) {
            if (csig) class_sig = csig;
            if (csig) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(csig));
            if (cgen) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(cgen));
        }
        // We have a JNI local ref to free if running on a JNI-attached thread.
        // Method-trace callbacks pass jni_env from JVMTI; deletion happens
        // in caller's frame. For safety (we may be on a thread without jni),
        // we DO NOT call DeleteLocalRef here. JVMTI's GetMethodDeclaringClass
        // returns a local ref via the JNI environment of the calling thread;
        // letting it lapse at frame-pop time is fine for our use.
    }

    std::pair<std::string, std::string> entry{class_sig, method_name};
    {
        std::lock_guard<std::mutex> lock(g_method_name_cache_mutex);
        // Re-check after acquiring lock in case another caller filled it.
        auto it = g_method_name_cache.find(method);
        if (it != g_method_name_cache.end()) return it->second;
        g_method_name_cache[method] = entry;
    }
    return entry;
}

// Resolve (full_jvm_signature, return_type_char) for a method; cached. The
// return-type char is the first character after the closing paren in the
// JVM signature. Examples:
//   "(II)I"                       -> ("(II)I", 'I')
//   "()V"                         -> ("()V",   'V')
//   "(Ljava/lang/String;)Ljava/lang/Object;" -> (sig, 'L')
//   "([I)[B"                      -> ("([I)[B", '[')
// Returns ({}, '\0') if JVMTI lookup fails (e.g., method id no longer valid).
std::pair<std::string, char> resolve_method_signature_cached(jmethodID method) {
    if (!method) return {std::string(), '\0'};
    {
        std::lock_guard<std::mutex> lock(g_method_signature_cache_mutex);
        auto it = g_method_signature_cache.find(method);
        if (it != g_method_signature_cache.end()) return it->second;
    }
    char* name_c = nullptr;
    char* sig_c = nullptr;
    char* generic_c = nullptr;
    if (g_jvmti->GetMethodName(method, &name_c, &sig_c, &generic_c) != JVMTI_ERROR_NONE) {
        return {std::string(), '\0'};
    }
    std::string full_sig = sig_c ? std::string(sig_c) : std::string();
    if (name_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name_c));
    if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
    if (generic_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(generic_c));

    char ret_char = '\0';
    auto rp = full_sig.find(')');
    if (rp != std::string::npos && rp + 1 < full_sig.size()) {
        ret_char = full_sig[rp + 1];
    }

    std::pair<std::string, char> entry{full_sig, ret_char};
    {
        std::lock_guard<std::mutex> lock(g_method_signature_cache_mutex);
        auto it = g_method_signature_cache.find(method);
        if (it != g_method_signature_cache.end()) return it->second;
        g_method_signature_cache[method] = entry;
    }
    return entry;
}

// Render a local-variable arg as a JSON object fragment:
//   {"name":"x","type":"int","value":42}
//   {"name":"s","type":"Ljava/lang/String;","value":"j@7f3a1c"}
// Returns "" if the local read fails (caller decides whether to skip or
// substitute a placeholder). `type_sig` is the JVM signature for the slot.
std::string render_local_arg(JNIEnv* jni, jthread thread, jint slot,
                             const std::string& name,
                             const std::string& type_sig) {
    if (type_sig.empty()) return std::string();
    char tc = type_sig[0];

    // Use depth=0 (top frame). The MethodEntry callback fires *after* the
    // frame has been pushed, so locals are addressable on depth 0.
    char buf[128];
    std::string out = "{\"name\":\"" + json_escape(name) + "\"";

    auto emit_int_like = [&](const char* type_label, jint v, bool as_bool) {
        out += ",\"type\":\"";
        out += type_label;
        out += "\"";
        if (as_bool) {
            out += ",\"value\":";
            out += (v != 0) ? "true" : "false";
        } else {
            snprintf(buf, sizeof(buf), ",\"value\":%d", v);
            out += buf;
        }
        out += "}";
    };

    switch (tc) {
        case 'I': {
            jint v = 0;
            if (g_jvmti->GetLocalInt(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            emit_int_like("int", v, false);
            return out;
        }
        case 'B': {
            jint v = 0;
            if (g_jvmti->GetLocalInt(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            emit_int_like("byte", v, false);
            return out;
        }
        case 'S': {
            jint v = 0;
            if (g_jvmti->GetLocalInt(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            emit_int_like("short", v, false);
            return out;
        }
        case 'C': {
            jint v = 0;
            if (g_jvmti->GetLocalInt(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            emit_int_like("char", v, false);
            return out;
        }
        case 'Z': {
            jint v = 0;
            if (g_jvmti->GetLocalInt(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            emit_int_like("boolean", v, true);
            return out;
        }
        case 'J': {
            jlong v = 0;
            if (g_jvmti->GetLocalLong(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            snprintf(buf, sizeof(buf), ",\"type\":\"long\",\"value\":%lld",
                static_cast<long long>(v));
            out += buf;
            out += "}";
            return out;
        }
        case 'F': {
            jfloat v = 0;
            if (g_jvmti->GetLocalFloat(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            snprintf(buf, sizeof(buf), ",\"type\":\"float\",\"value\":%g",
                static_cast<double>(v));
            out += buf;
            out += "}";
            return out;
        }
        case 'D': {
            jdouble v = 0;
            if (g_jvmti->GetLocalDouble(thread, 0, slot, &v) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            snprintf(buf, sizeof(buf), ",\"type\":\"double\",\"value\":%g",
                static_cast<double>(v));
            out += buf;
            out += "}";
            return out;
        }
        case 'L':
        case '[': {
            jobject obj = nullptr;
            if (g_jvmti->GetLocalObject(thread, 0, slot, &obj) != JVMTI_ERROR_NONE) {
                return std::string();
            }
            out += ",\"type\":\"" + json_escape(type_sig) + "\"";
            if (!obj) {
                out += ",\"value\":\"null\"}";
                return out;
            }
            // Resolve runtime class if a JNIEnv is available — arrays show up
            // as "[I" etc. and JNI's GetObjectClass works on them too.
            std::string runtime_type;
            if (jni) {
                jclass kls = jni->GetObjectClass(obj);
                if (kls) {
                    runtime_type = resolve_class_sig_for_jclass(jni, kls);
                    jni->DeleteLocalRef(kls);
                }
            }
            if (!runtime_type.empty()) {
                out += ",\"runtime_type\":\"" + json_escape(runtime_type) + "\"";
            }
            // Stable handle — NOT a NewGlobalRef. The agent already mints
            // vobj#<id> refs for heap walks; method-trace args don't need to
            // be reach-back-addressable (the buffer is read-once + replayed
            // to the server). Use the raw jobject bit pattern instead so the
            // string is stable for the lifetime of this event.
            uintptr_t bits = reinterpret_cast<uintptr_t>(obj);
            snprintf(buf, sizeof(buf), ",\"value\":\"j@%llx\"",
                static_cast<unsigned long long>(bits));
            out += buf;
            out += "}";
            if (jni) jni->DeleteLocalRef(obj);
            return out;
        }
        default:
            return std::string();
    }
}

// Capture entry-time args for the current frame. Returns (args_json, args_absent).
//   - args_json is always a complete JSON array literal ("[]" if no args).
//   - args_absent is true when GetLocalVariableTable returns ABSENT_INFORMATION
//     (release / R8-stripped builds). Caller emits "args_absent":true alongside.
//
// The implementation uses GetArgumentsSize() to find the cutoff slot ("the
// first slot after args") and GetLocalVariableTable() to map slot -> (name,
// signature). For static methods slot 0 is the first arg; for instance
// methods slot 0 is `this` and is intentionally INCLUDED (named "this") so
// the receiver is visible.
std::pair<std::string, bool> capture_method_entry_args(JNIEnv* jni,
                                                       jthread thread,
                                                       jmethodID method) {
    if (!method) return {"[]", false};

    jint args_size = 0;
    jvmtiError ase = g_jvmti->GetArgumentsSize(method, &args_size);
    if (ase != JVMTI_ERROR_NONE || args_size <= 0) {
        // Either native method, no args, or lookup failed. Empty array is the
        // honest answer; not "absent" (that's reserved for missing local
        // variable table).
        return {"[]", false};
    }

    jint count = 0;
    jvmtiLocalVariableEntry* entries = nullptr;
    jvmtiError e = g_jvmti->GetLocalVariableTable(method, &count, &entries);
    if (e == JVMTI_ERROR_ABSENT_INFORMATION) {
        return {"[]", true};
    }
    if (e != JVMTI_ERROR_NONE || !entries || count <= 0) {
        return {"[]", true};
    }

    // Filter to entries whose start_location <= 0 (visible at method entry)
    // and slot < args_size. Sort by slot so positional ordering is stable.
    struct ArgRow {
        jint slot;
        std::string name;
        std::string sig;
    };
    std::vector<ArgRow> rows;
    rows.reserve(static_cast<size_t>(args_size));
    for (jint i = 0; i < count; i++) {
        const jvmtiLocalVariableEntry& en = entries[i];
        // start_location 0 == visible from the very first instruction. Long
        // and double take two slots; we just trust slot < args_size.
        if (en.start_location > 0) continue;
        if (en.slot < 0 || en.slot >= args_size) continue;
        ArgRow row;
        row.slot = en.slot;
        row.name = en.name ? std::string(en.name) : std::string();
        row.sig = en.signature ? std::string(en.signature) : std::string();
        rows.push_back(std::move(row));
    }

    // Deallocate the table — every per-entry name/signature/generic plus the
    // entries array itself.
    for (jint i = 0; i < count; i++) {
        if (entries[i].name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].name));
        if (entries[i].signature) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].signature));
        if (entries[i].generic_signature) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries[i].generic_signature));
    }
    g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(entries));

    if (rows.empty()) {
        // Table existed but no rows matched our filter. Treat as absent: the
        // method has args (args_size > 0) but the table is stripped enough
        // that we couldn't recover names.
        return {"[]", true};
    }

    std::sort(rows.begin(), rows.end(),
        [](const ArgRow& a, const ArgRow& b) { return a.slot < b.slot; });

    std::string out;
    out.reserve(64 + rows.size() * 48);
    out += "[";
    bool any_emitted = false;
    for (const auto& r : rows) {
        std::string frag = render_local_arg(jni, thread, r.slot, r.name, r.sig);
        if (frag.empty()) continue;
        if (any_emitted) out += ",";
        out += frag;
        any_emitted = true;
    }
    out += "]";

    if (!any_emitted) {
        // We had rows but every render failed (rare — usually a JVMTI
        // mid-call hiccup). Surface as absent so the caller can flag it.
        return {"[]", true};
    }
    return {std::move(out), false};
}

// Render a return value as a single JSON value (NOT wrapped in a field).
//
//   void              -> "null"  (caller emits "void":true separately)
//   primitive         -> number / "true" / "false" / char-as-int
//   reference / array -> {"type":"<sig>","ref":"j@<hex>"} or "null"
//
// `full_sig` is used for the rendered reference type when the runtime class
// can't be resolved (e.g., a null jobject). The actual runtime class, if
// non-null, is preferred — same convention as render_local_arg.
std::string render_return_value(JNIEnv* jni, char ret_type_char,
                                const std::string& full_sig,
                                const jvalue& v) {
    char buf[96];
    switch (ret_type_char) {
        case 'V':
            return "null";
        case 'I': {
            snprintf(buf, sizeof(buf), "%d", v.i);
            return std::string(buf);
        }
        case 'J': {
            snprintf(buf, sizeof(buf), "%lld", static_cast<long long>(v.j));
            return std::string(buf);
        }
        case 'F': {
            snprintf(buf, sizeof(buf), "%g", static_cast<double>(v.f));
            return std::string(buf);
        }
        case 'D': {
            snprintf(buf, sizeof(buf), "%g", static_cast<double>(v.d));
            return std::string(buf);
        }
        case 'Z':
            return v.z ? std::string("true") : std::string("false");
        case 'B': {
            snprintf(buf, sizeof(buf), "%d", static_cast<int>(v.b));
            return std::string(buf);
        }
        case 'S': {
            snprintf(buf, sizeof(buf), "%d", static_cast<int>(v.s));
            return std::string(buf);
        }
        case 'C': {
            // Render as int code point — safer than emitting a single utf-8
            // codepoint in JSON when the value might be surrogate or control.
            snprintf(buf, sizeof(buf), "%d", static_cast<int>(v.c));
            return std::string(buf);
        }
        case 'L':
        case '[': {
            jobject obj = v.l;
            if (!obj) return "null";
            // Pull the declared return signature from full_sig (substring
            // after ')'); fall back to "Ljava/lang/Object;" if missing.
            std::string declared_sig;
            auto rp = full_sig.find(')');
            if (rp != std::string::npos && rp + 1 < full_sig.size()) {
                declared_sig = full_sig.substr(rp + 1);
            }
            std::string runtime_sig;
            if (jni) {
                jclass kls = jni->GetObjectClass(obj);
                if (kls) {
                    runtime_sig = resolve_class_sig_for_jclass(jni, kls);
                    jni->DeleteLocalRef(kls);
                }
            }
            const std::string& type_for_emit = !runtime_sig.empty()
                ? runtime_sig : declared_sig;
            std::string out = "{\"type\":\"";
            out += json_escape(type_for_emit);
            out += "\",\"ref\":\"";
            uintptr_t bits = reinterpret_cast<uintptr_t>(obj);
            snprintf(buf, sizeof(buf), "j@%llx",
                static_cast<unsigned long long>(bits));
            out += buf;
            out += "\"}";
            // Do NOT DeleteLocalRef — the jvalue's `l` field is owned by the
            // JVMTI dispatch frame; we just borrow it for read.
            return out;
        }
        default:
            // Unknown / corrupted signature — return null and let the caller
            // continue.
            return "null";
    }
}

// Resolve a thread name via JVMTI. Returns "" on failure.
std::string resolve_thread_name(jthread thread) {
    if (!thread) return std::string();
    jvmtiThreadInfo info{};
    if (g_jvmti->GetThreadInfo(thread, &info) != JVMTI_ERROR_NONE) return std::string();
    std::string name = info.name ? std::string(info.name) : std::string();
    if (info.name) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(info.name));
    return name;
}

// Mint a vobj#<id> ref from either a local or global jobject. The input ref is
// promoted to a global ref (caller can DeleteLocalRef on theirs). Returns the
// numeric portion (the caller composes "vobj#<n>").
int mint_v16_ref(JNIEnv* jni, jobject local_or_global) {
    if (!jni || !local_or_global) return 0;
    jobject gref = jni->NewGlobalRef(local_or_global);
    if (!gref) return 0;
    int id = g_v16_next_ref_id.fetch_add(1, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(g_v16_refs_mutex);
        g_v16_refs[id] = gref;
    }
    return id;
}

// Resolve a vobj#<id> ref to its jobject (still a global ref). Returns false if
// the id is unknown.
bool resolve_v16_ref(int ref_id, jobject& out) {
    std::lock_guard<std::mutex> lock(g_v16_refs_mutex);
    auto it = g_v16_refs.find(ref_id);
    if (it == g_v16_refs.end()) return false;
    out = it->second;
    return true;
}

// Drop and free every minted vobj#<id> ref. Used on client disconnect — those
// ids only have meaning to the connected session.
void release_v16_refs(JNIEnv* jni) {
    std::map<int, jobject> to_release;
    {
        std::lock_guard<std::mutex> lock(g_v16_refs_mutex);
        std::swap(to_release, g_v16_refs);
    }
    if (!jni) return;
    for (auto& kv : to_release) {
        if (kv.second) jni->DeleteGlobalRef(kv.second);
    }
}

PerThreadState* get_or_create_per_thread() {
    pid_t tid = static_cast<pid_t>(syscall(SYS_gettid));
    std::lock_guard<std::mutex> lock(g_per_thread_mutex);
    auto it = g_per_thread_states.find(tid);
    if (it != g_per_thread_states.end()) return it->second.get();
    auto up = std::unique_ptr<PerThreadState>(new PerThreadState());
    PerThreadState* raw = up.get();
    g_per_thread_states.emplace(tid, std::move(up));
    return raw;
}

// Strip the leading "vobj#" prefix and return the numeric id (or -1 if not a
// valid agent-minted ref). Defensive against malformed input.
int parse_vobj_ref(const std::string& s) {
    const char* prefix = "vobj#";
    size_t plen = strlen(prefix);
    if (s.size() <= plen) return -1;
    if (s.compare(0, plen, prefix) != 0) return -1;
    long long v = strtoll(s.c_str() + plen, nullptr, 10);
    if (v <= 0 || v > INT32_MAX) return -1;
    return static_cast<int>(v);
}

// Re-install the global event-callbacks struct with JVMTI. Called whenever
// individual callbacks are toggled. Returns true on success.
bool refresh_event_callbacks() {
    std::lock_guard<std::mutex> lock(g_event_cb_mutex);
    jvmtiError err = g_jvmti->SetEventCallbacks(&g_event_callbacks,
        sizeof(g_event_callbacks));
    if (err != JVMTI_ERROR_NONE) {
        LOGE("SetEventCallbacks failed: %d", err);
        return false;
    }
    return true;
}

// ---------------- v1.6 trace session types ----------------

// Filter kinds for method-trace sessions.
enum class TraceFilterKind {
    Methods,
    ClassPattern,
    MethodRegex,
};

struct MethodEvent {
    char kind;                   // 'E' (entry) or 'X' (exit)
    std::string class_sig;
    std::string method_name;
    std::string thread_name;
    long long nano_time = 0;
    int depth = 0;
    std::string args_json;       // empty if not captured (entry only)
    bool args_absent = false;    // GetLocalVariableTable returned ABSENT_INFORMATION
    std::string return_json;     // empty if not captured / not exit
    bool return_is_void = false; // exit-only; true if method signature returns V
    bool was_popped_by_exception = false;  // exit-only; from JVMTI MethodExit
    long long elapsed_ns = -1;   // exit only; -1 = not provided
};

struct AllocEvent {
    std::string class_sig;
    std::string thread_name;
    long long nano_time = 0;
    long long size_bytes = 0;
    // Stack frames already serialized to per-frame JSON-fragments.
    std::vector<std::string> stack_frames;
};

struct MethodTraceSession {
    std::string buffer_id;
    long long started_at_ms = 0;
    TraceFilterKind filter_kind = TraceFilterKind::Methods;

    // For Methods:
    std::unordered_set<std::string> method_set;       // "Lsig;methodName" strings
    // For ClassPattern with literal prefix:
    bool class_pattern_is_pure_prefix = false;
    std::unordered_set<std::string> class_sig_allowlist;
    // For ClassPattern via regex (when wildcards are non-prefix) and MethodRegex:
    std::regex class_regex;
    bool has_class_regex = false;
    std::regex method_regex;
    bool has_method_regex = false;

    bool include_args = false;
    bool include_return = false;
    bool kind_entry = true;
    bool kind_exit = true;
    int max_events_per_sec = 1000;
    double sample_rate = 1.0;
    size_t buffer_cap = 10000;
    int estimated_match_count = 0;

    // Buffer + throttling state, all guarded by buf_mutex.
    std::mutex buf_mutex;
    std::deque<MethodEvent> events;
    long long last_throttle_second_ms = 0;
    int events_this_second = 0;
    long long dropped_total = 0;
    long long dropped_since_read = 0;
};

struct AllocTraceSession {
    std::string buffer_id;
    long long started_at_ms = 0;
    std::vector<jclass> class_global_refs;
    // Raw pointers for fast filter. ART jclass pointers are stable for the
    // lifetime of the global ref we hold.
    std::unordered_set<jclass> class_set;
    int capture_stack_depth = 0;
    int max_events_per_sec = 1000;
    double sample_rate = 1.0;
    size_t buffer_cap = 10000;

    std::mutex buf_mutex;
    std::deque<AllocEvent> events;
    long long last_throttle_second_ms = 0;
    int events_this_second = 0;
    long long dropped_total = 0;
    long long dropped_since_read = 0;
};

// Convert a glob (with `*` and `?`) into a regex pattern. `.` is escaped to
// `\\.`. `*` becomes `.*`, `?` becomes `.`. Other regex metas are escaped.
std::string glob_to_regex(const std::string& glob) {
    std::string out;
    out.reserve(glob.size() * 2 + 4);
    out.push_back('^');
    for (char c : glob) {
        switch (c) {
            case '*': out += ".*"; break;
            case '?': out += "."; break;
            case '.': out += "\\."; break;
            case '\\': out += "\\\\"; break;
            case '+': case '(': case ')': case '[': case ']':
            case '{': case '}': case '^': case '$': case '|':
                out.push_back('\\'); out.push_back(c); break;
            default: out.push_back(c);
        }
    }
    out.push_back('$');
    return out;
}

// Convert a dotted FQN ("com.foo.Bar") into a JVM internal-form signature
// fragment ("Lcom/foo/Bar;"). Wildcards (`*`, `?`) are preserved.
std::string dotted_to_signature_glob(const std::string& dotted) {
    std::string out;
    out.reserve(dotted.size() + 2);
    out.push_back('L');
    for (char c : dotted) {
        out.push_back(c == '.' ? '/' : c);
    }
    out.push_back(';');
    return out;
}

// Is the dotted pattern a pure-prefix glob? "com.foo.*" -> yes. "com.*.Bar"
// or "com.Foo?" -> no.
bool is_pure_prefix_glob(const std::string& dotted) {
    // Allow at most a single trailing ".*" and no other wildcards.
    if (dotted.empty()) return false;
    size_t star = dotted.find('*');
    if (star == std::string::npos) return true;          // exact name
    if (star != dotted.size() - 1) return false;         // wildcard not at end
    // The char before the * must be a '.' to ensure prefix semantics.
    if (star == 0) return false;
    return dotted[star - 1] == '.';
}

// Pre-resolve every loaded class whose signature starts with `sig_prefix`
// (e.g. "Lcom/foo/"). Returns the set of matching signatures.
std::unordered_set<std::string> resolve_class_signatures_with_prefix(
        const std::string& sig_prefix) {
    std::unordered_set<std::string> out;
    jint count = 0;
    jclass* classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE) return out;
    for (jint i = 0; i < count; ++i) {
        char* sig_c = nullptr;
        char* gen_c = nullptr;
        if (g_jvmti->GetClassSignature(classes[i], &sig_c, &gen_c) == JVMTI_ERROR_NONE) {
            if (sig_c && std::strncmp(sig_c, sig_prefix.c_str(), sig_prefix.size()) == 0) {
                out.insert(sig_c);
            }
            if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
            if (gen_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(gen_c));
        }
    }
    if (classes) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return out;
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

// ---------------- v1.6 RPC handlers ----------------

// Collect every loaded class whose signature matches `signature` (across all
// classloaders). Returns LOCAL refs that callers must DeleteLocalRef.
std::vector<jclass> find_all_loaded_classes_by_signature(JNIEnv* jni,
        const std::string& signature) {
    std::vector<jclass> out;
    jint count = 0;
    jclass* classes = nullptr;
    if (g_jvmti->GetLoadedClasses(&count, &classes) != JVMTI_ERROR_NONE) return out;
    for (jint i = 0; i < count; ++i) {
        char* sig_c = nullptr;
        char* gen_c = nullptr;
        if (g_jvmti->GetClassSignature(classes[i], &sig_c, &gen_c) == JVMTI_ERROR_NONE) {
            if (sig_c && signature == sig_c) {
                out.push_back(static_cast<jclass>(jni->NewLocalRef(classes[i])));
            }
            if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
            if (gen_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(gen_c));
        }
    }
    if (classes) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(classes));
    return out;
}

// agent.heap_count_instances state passed via user_data.
struct HeapCountState {
    long long count = 0;
    long long total_size = 0;
};

// IterateThroughHeap callback (JVMTI 1.2). Returns a bitmask: 0 means continue
// without visiting children (we only need a flat count of instances of `klass`).
jint JNICALL heap_count_cb(jlong /*class_tag*/, jlong size,
        jlong* /*tag_ptr*/, jint /*length*/, void* user_data) {
    auto* st = static_cast<HeapCountState*>(user_data);
    if (st) {
        st->count++;
        st->total_size += size;
    }
    return 0;
}

std::string handle_heap_count_instances(JNIEnv* jni, const std::string& line,
        bool& is_error) {
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
    std::string consistency = "strong";
    size_t cons_pos = find_json_key(line, params_pos, "consistency");
    if (cons_pos != std::string::npos) {
        size_t t2 = cons_pos;
        std::string v = read_json_string(line, t2);
        if (!v.empty()) consistency = v;
    }

    auto classes = find_all_loaded_classes_by_signature(jni, signature);
    if (classes.empty()) {
        is_error = true;
        return error_json(-32010, "class_not_loaded",
            "{\"class_signature\":\"" + json_escape(signature) + "\"}");
    }

    HeapCountState st;
    jvmtiError walk_err = JVMTI_ERROR_NONE;
    jvmtiHeapCallbacks count_callbacks{};
    count_callbacks.heap_iteration_callback = heap_count_cb;
    for (jclass jc : classes) {
        // IterateThroughHeap with klass as the class filter; heap_filter=0
        // (visit every object regardless of tag/class-tag state).
        jvmtiError err = g_jvmti->IterateThroughHeap(
            /*heap_filter=*/0, jc, &count_callbacks, &st);
        if (err != JVMTI_ERROR_NONE) {
            walk_err = err;
        }
        jni->DeleteLocalRef(jc);
    }
    if (walk_err != JVMTI_ERROR_NONE && st.count == 0) {
        is_error = true;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(walk_err));
        return error_json(-32020, "jvmti_error",
            std::string("{\"jvmti_error\":") + buf + "}");
    }

    long long avg = (st.count > 0) ? (st.total_size / st.count) : 0;
    char buf[64];
    std::string out = "{\"class_signature\":\"" + json_escape(signature) + "\"";
    snprintf(buf, sizeof(buf), ",\"count\":%lld", st.count); out += buf;
    snprintf(buf, sizeof(buf), ",\"sample_size_bytes\":%lld", avg); out += buf;
    out += ",\"consistency\":\"" + json_escape(consistency) + "\"}";
    is_error = false;
    return out;
}

// agent.heap_iterate_by_class — tag matching instances with a unique op_tag,
// then GetObjectsWithTags to materialize up to `max` of them.
struct HeapIterateState {
    jlong op_tag = 0;
    long long total = 0;
    long long max_to_tag = 0;
};

// IterateThroughHeap callback (JVMTI 1.2). Returns 0 to continue without
// descending into children — we only need to tag direct instances of `klass`.
jint JNICALL heap_iterate_cb(jlong /*class_tag*/, jlong /*size*/,
        jlong* tag_ptr, jint /*length*/, void* user_data) {
    auto* st = static_cast<HeapIterateState*>(user_data);
    if (!st || !tag_ptr) return 0;
    st->total++;
    if (st->total <= st->max_to_tag) {
        *tag_ptr = st->op_tag;
    }
    return 0;
}

std::string handle_heap_iterate_by_class(JNIEnv* jni, const std::string& line,
        bool& is_error) {
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
    long long max_v = 0;
    size_t max_pos = find_json_key(line, params_pos, "max");
    if (max_pos != std::string::npos) {
        size_t t2 = max_pos; max_v = read_json_int(line, t2);
    }
    if (max_v <= 0) {
        is_error = true;
        return error_json(-32602, "invalid_params: max must be > 0");
    }
    if (max_v > 10000) max_v = 10000;

    auto classes = find_all_loaded_classes_by_signature(jni, signature);
    if (classes.empty()) {
        is_error = true;
        return error_json(-32010, "class_not_loaded",
            "{\"class_signature\":\"" + json_escape(signature) + "\"}");
    }

    HeapIterateState st;
    st.op_tag = g_v16_next_tag.fetch_add(1, std::memory_order_relaxed);
    st.max_to_tag = max_v;
    jvmtiError walk_err = JVMTI_ERROR_NONE;
    jvmtiHeapCallbacks iter_callbacks{};
    iter_callbacks.heap_iteration_callback = heap_iterate_cb;
    for (jclass jc : classes) {
        // IterateThroughHeap with klass as the class filter; heap_filter=0
        // (visit every object). Callback tags up to max_to_tag instances and
        // counts the rest for the `truncated` flag.
        jvmtiError err = g_jvmti->IterateThroughHeap(
            /*heap_filter=*/0, jc, &iter_callbacks, &st);
        if (err != JVMTI_ERROR_NONE) walk_err = err;
        jni->DeleteLocalRef(jc);
    }
    if (walk_err != JVMTI_ERROR_NONE && st.total == 0) {
        is_error = true;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(walk_err));
        return error_json(-32020, "jvmti_error",
            std::string("{\"jvmti_error\":") + buf + "}");
    }

    // Materialize tagged objects.
    jlong tags[1] = {st.op_tag};
    jint got = 0;
    jobject* obj_arr = nullptr;
    jlong* tag_arr = nullptr;
    jvmtiError ge = g_jvmti->GetObjectsWithTags(1, tags, &got, &obj_arr, &tag_arr);
    if (ge != JVMTI_ERROR_NONE) {
        if (obj_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(obj_arr));
        if (tag_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(tag_arr));
        is_error = true;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(ge));
        return error_json(-32020, "jvmti_error",
            std::string("{\"jvmti_error\":") + buf + "}");
    }

    // Build "instances": each entry needs ref + size_bytes. Cap to max_v.
    std::string instances_json = "[";
    int emitted = 0;
    for (jint i = 0; i < got && emitted < max_v; ++i) {
        jobject obj = obj_arr[i];
        if (!obj) continue;
        // Clear the per-op tag from this object before promoting.
        g_jvmti->SetTag(obj, 0);
        // Compute size via GetObjectSize.
        jlong size_b = 0;
        g_jvmti->GetObjectSize(obj, &size_b);
        int ref_id = mint_v16_ref(jni, obj);
        // The jobject local ref returned by GetObjectsWithTags must be freed.
        jni->DeleteLocalRef(obj);
        if (ref_id == 0) continue;
        char buf[64];
        if (emitted > 0) instances_json += ",";
        snprintf(buf, sizeof(buf), "{\"ref\":\"vobj#%d\",\"size_bytes\":%lld}",
            ref_id, static_cast<long long>(size_b));
        instances_json += buf;
        emitted++;
    }
    // Clear tags from any remaining (above-cap) jobjects, free their local refs.
    for (jint i = static_cast<jint>(max_v); i < got; ++i) {
        if (obj_arr[i]) {
            g_jvmti->SetTag(obj_arr[i], 0);
            jni->DeleteLocalRef(obj_arr[i]);
        }
    }
    if (obj_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(obj_arr));
    if (tag_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(tag_arr));
    instances_json += "]";

    bool truncated = (st.total > emitted);
    char buf[64];
    std::string out = "{\"class_signature\":\"" + json_escape(signature) + "\"";
    out += ",\"instances\":" + instances_json;
    snprintf(buf, sizeof(buf), ",\"total\":%lld", st.total); out += buf;
    out += truncated ? ",\"truncated\":true" : ",\"truncated\":false";
    out += "}";
    is_error = false;
    return out;
}

// Map a jvmtiHeapReferenceKind to a short edge-kind string.
const char* heap_ref_kind_to_string(jvmtiHeapReferenceKind k) {
    switch (k) {
        case JVMTI_HEAP_REFERENCE_CLASS:           return "class";
        case JVMTI_HEAP_REFERENCE_FIELD:           return "field";
        case JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT:   return "array_element";
        case JVMTI_HEAP_REFERENCE_CLASS_LOADER:    return "class_loader";
        case JVMTI_HEAP_REFERENCE_SIGNERS:         return "signers";
        case JVMTI_HEAP_REFERENCE_PROTECTION_DOMAIN:return "protection_domain";
        case JVMTI_HEAP_REFERENCE_INTERFACE:       return "interface";
        case JVMTI_HEAP_REFERENCE_STATIC_FIELD:    return "static_field";
        case JVMTI_HEAP_REFERENCE_CONSTANT_POOL:   return "constant_pool";
        case JVMTI_HEAP_REFERENCE_SUPERCLASS:      return "superclass";
        case JVMTI_HEAP_REFERENCE_JNI_GLOBAL:      return "jni_global";
        case JVMTI_HEAP_REFERENCE_SYSTEM_CLASS:    return "system_class";
        case JVMTI_HEAP_REFERENCE_MONITOR:         return "monitor";
        case JVMTI_HEAP_REFERENCE_STACK_LOCAL:     return "stack_local";
        case JVMTI_HEAP_REFERENCE_JNI_LOCAL:       return "jni_local";
        case JVMTI_HEAP_REFERENCE_THREAD:          return "thread";
        case JVMTI_HEAP_REFERENCE_OTHER:           return "other";
        default:                                   return "unknown";
    }
}

// Resolve the field name for a referrer whose edge_kind is FIELD or STATIC_FIELD.
// `field_index` comes from `reference_info->field.index`. Returns "" on failure.
std::string resolve_field_name(jclass owner_class, jint field_index) {
    if (!owner_class || field_index < 0) return std::string();
    jint count = 0;
    jfieldID* fields = nullptr;
    if (g_jvmti->GetClassFields(owner_class, &count, &fields) != JVMTI_ERROR_NONE) {
        return std::string();
    }
    std::string out;
    if (field_index < count && fields) {
        char* name_c = nullptr;
        char* sig_c = nullptr;
        char* gen_c = nullptr;
        if (g_jvmti->GetFieldName(owner_class, fields[field_index],
                &name_c, &sig_c, &gen_c) == JVMTI_ERROR_NONE) {
            if (name_c) out = name_c;
            if (name_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(name_c));
            if (sig_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(sig_c));
            if (gen_c) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(gen_c));
        }
    }
    if (fields) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(fields));
    return out;
}

// agent.heap_find_referrers state.
struct ReferrerRecord {
    jvmtiHeapReferenceKind kind;
    jint field_index;      // FIELD/STATIC_FIELD: field slot
    jint array_index;      // ARRAY_ELEMENT
    jlong referrer_class_tag;
};

struct HeapReferrersState {
    jlong target_tag = 0;
    jlong referrer_tag = 0;
    long long total = 0;
    long long max_to_mark = 0;
    // Map from referrer_class_tag (set transiently below) -> referrer kind/info.
    // We can't read the referrer's identity in the callback (no jobject), so we
    // tag and then identify after the walk via GetObjectsWithTags. Edge metadata
    // is recorded here keyed by ID we encode in the referrer_tag itself.
    // Strategy: assign each interesting referrer a unique tag in a contiguous
    // band starting from `referrer_tag`. Records[i] describes object with
    // tag `referrer_tag + i`.
    std::vector<ReferrerRecord> records;
};

jint JNICALL heap_referrers_cb(jvmtiHeapReferenceKind reference_kind,
        const jvmtiHeapReferenceInfo* reference_info, jlong /*class_tag*/,
        jlong referrer_class_tag, jlong /*size*/, jlong* tag_ptr,
        jlong* referrer_tag_ptr, jint /*length*/, void* user_data) {
    auto* st = static_cast<HeapReferrersState*>(user_data);
    if (!st || !tag_ptr || !referrer_tag_ptr) return JVMTI_VISIT_OBJECTS;
    // We care only about edges that POINT AT the target.
    if (*tag_ptr != st->target_tag) return JVMTI_VISIT_OBJECTS;
    // Roots have no referrer object — skip if referrer_tag_ptr can't be set.
    // (jvmti still passes a non-null pointer for root edges, but writing a tag
    // would tag... nothing visible; we capture root kinds anyway.)
    st->total++;
    if (st->total > st->max_to_mark) return JVMTI_VISIT_OBJECTS;

    // Allocate a unique band tag.
    jlong band_tag = st->referrer_tag + static_cast<jlong>(st->records.size());
    // Skip if this referrer is already tagged (we'd double-count or
    // overwrite). Mark only if *referrer_tag_ptr is currently 0.
    if (*referrer_tag_ptr != 0) return JVMTI_VISIT_OBJECTS;
    *referrer_tag_ptr = band_tag;

    ReferrerRecord rec{};
    rec.kind = reference_kind;
    rec.field_index = -1;
    rec.array_index = -1;
    rec.referrer_class_tag = referrer_class_tag;
    if (reference_info) {
        if (reference_kind == JVMTI_HEAP_REFERENCE_FIELD ||
            reference_kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD) {
            rec.field_index = reference_info->field.index;
        } else if (reference_kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT) {
            rec.array_index = reference_info->array.index;
        }
    }
    st->records.push_back(rec);
    return JVMTI_VISIT_OBJECTS;
}

std::string handle_heap_find_referrers(JNIEnv* jni, const std::string& line,
        bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t ref_pos = find_json_key(line, params_pos, "ref");
    if (ref_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing ref");
    }
    size_t tmp = ref_pos;
    std::string ref_str = read_json_string(line, tmp);
    int ref_id = parse_vobj_ref(ref_str);
    if (ref_id < 0) {
        is_error = true;
        return error_json(-32602, "invalid_params: ref must be vobj#<id>");
    }
    long long max_v = 0;
    size_t max_pos = find_json_key(line, params_pos, "max");
    if (max_pos != std::string::npos) {
        size_t t2 = max_pos; max_v = read_json_int(line, t2);
    }
    if (max_v <= 0) {
        is_error = true;
        return error_json(-32602, "invalid_params: max must be > 0");
    }
    if (max_v > 10000) max_v = 10000;

    jobject target = nullptr;
    if (!resolve_v16_ref(ref_id, target) || !target) {
        is_error = true;
        return error_json(-32011, "unknown_ref",
            "{\"ref\":\"" + json_escape(ref_str) + "\"}");
    }

    // Pick fresh tags. We need: target_tag for the source; referrer_tag is the
    // base of a band that grows by 1 per recorded referrer.
    jlong target_tag = g_v16_next_tag.fetch_add(1, std::memory_order_relaxed);
    jlong referrer_base = g_v16_next_tag.fetch_add(max_v + 1,
        std::memory_order_relaxed);

    if (g_jvmti->SetTag(target, target_tag) != JVMTI_ERROR_NONE) {
        is_error = true;
        return error_json(-32020, "jvmti_error", "{\"jvmti_error\":-1}");
    }

    HeapReferrersState st;
    st.target_tag = target_tag;
    st.referrer_tag = referrer_base;
    st.max_to_mark = max_v;

    jvmtiHeapCallbacks callbacks{};
    callbacks.heap_reference_callback = heap_referrers_cb;
    jvmtiError ferr = g_jvmti->FollowReferences(0, nullptr, nullptr, &callbacks, &st);
    if (ferr != JVMTI_ERROR_NONE) {
        g_jvmti->SetTag(target, 0);
        is_error = true;
        char buf[32];
        snprintf(buf, sizeof(buf), "%d", static_cast<int>(ferr));
        return error_json(-32020, "jvmti_error",
            std::string("{\"jvmti_error\":") + buf + "}");
    }

    // Materialize each referrer one band-tag at a time. (GetObjectsWithTags
    // accepts an array of tags but we want to preserve the per-record kind
    // mapping, which is cleanest one-at-a-time.)
    std::string referrers_json = "[";
    int emitted = 0;
    for (size_t i = 0; i < st.records.size(); ++i) {
        jlong band_tag = referrer_base + static_cast<jlong>(i);
        jlong tags[1] = {band_tag};
        jint got = 0;
        jobject* obj_arr = nullptr;
        jlong* tag_arr = nullptr;
        if (g_jvmti->GetObjectsWithTags(1, tags, &got, &obj_arr, &tag_arr)
                != JVMTI_ERROR_NONE) continue;
        if (got > 0 && obj_arr && obj_arr[0]) {
            jobject ref_obj = obj_arr[0];
            // Clear the band tag.
            g_jvmti->SetTag(ref_obj, 0);
            // Resolve referrer's type.
            jclass ref_klass = jni->GetObjectClass(ref_obj);
            std::string ref_type = resolve_class_sig_for_jclass(jni, ref_klass);
            // Mint a vobj for the referrer.
            int new_id = mint_v16_ref(jni, ref_obj);
            // Resolve field / array detail.
            std::string edge_str = heap_ref_kind_to_string(st.records[i].kind);
            std::string edge_detail;
            if (st.records[i].kind == JVMTI_HEAP_REFERENCE_FIELD) {
                edge_detail = resolve_field_name(ref_klass, st.records[i].field_index);
            } else if (st.records[i].kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD) {
                edge_detail = resolve_field_name(ref_klass, st.records[i].field_index);
            } else if (st.records[i].kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT) {
                char abuf[32];
                snprintf(abuf, sizeof(abuf), "%d", st.records[i].array_index);
                edge_detail = abuf;
            }
            if (ref_klass) jni->DeleteLocalRef(ref_klass);
            jni->DeleteLocalRef(ref_obj);
            if (new_id != 0) {
                if (emitted > 0) referrers_json += ",";
                referrers_json += "{\"ref\":\"vobj#";
                char nbuf[32]; snprintf(nbuf, sizeof(nbuf), "%d", new_id);
                referrers_json += nbuf;
                referrers_json += "\",\"type\":\"" + json_escape(ref_type);
                referrers_json += "\",\"edge\":\"" + json_escape(edge_str) + "\"";
                if (!edge_detail.empty()) {
                    referrers_json += ",\"edge_detail\":\"" +
                        json_escape(edge_detail) + "\"";
                }
                referrers_json += "}";
                emitted++;
            }
        }
        if (obj_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(obj_arr));
        if (tag_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(tag_arr));
    }
    referrers_json += "]";

    g_jvmti->SetTag(target, 0);

    bool truncated = (st.total > emitted);
    char buf[64];
    std::string out = "{\"ref\":\"" + json_escape(ref_str) + "\"";
    out += ",\"referrers\":" + referrers_json;
    snprintf(buf, sizeof(buf), ",\"total\":%lld", st.total); out += buf;
    out += truncated ? ",\"truncated\":true" : ",\"truncated\":false";
    out += "}";
    is_error = false;
    return out;
}

// ---- agent.heap_find_referrer_chain ----
//
// Iterative reverse BFS: each layer tags its frontier, walks the heap once,
// and collects edges into that frontier. Edges originating from roots
// terminate chains immediately. Non-root inbound edges enroll their source
// into the next-layer frontier.
//
// Node bookkeeping table: each visited node gets an int "chain node id"; we
// record (id, vobj_id, type, parent_id, edge_kind, edge_detail, root_kind).

struct ChainNode {
    int node_id = 0;
    int vobj_id = 0;            // 0 if not yet minted (target node uses caller's vobj)
    jobject gref = nullptr;     // owned; freed when this scratch state tears down
    std::string type;
    int parent_node_id = -1;    // -1 = no parent (root or target)
    std::string edge_kind;
    std::string edge_detail;
    std::string root_kind;      // non-empty -> this node is a chain terminator
    int depth = 0;              // 0 = target; 1 = direct referrer; ...
};

struct ChainBFSState {
    jlong current_target_tag = 0;
    jlong next_tag_base = 0;
    int next_record_index = 0;
    int max_records_per_layer = 0;
    // (band tag) -> chain-node-record-index, set in the callback.
    // Encoded by allocating ascending tags from next_tag_base.
    struct LayerRecord {
        jvmtiHeapReferenceKind kind;
        jint field_index;
        jint array_index;
        jlong referrer_class_tag;
    };
    std::vector<LayerRecord> layer_records;
    // For the root case (referrer_tag_ptr is null/not writable for roots),
    // some root kinds still come through the heap_reference_callback. We
    // record them in `root_kind` outputs even though there's no concrete
    // referrer object.
    std::vector<std::pair<jvmtiHeapReferenceKind, /*kind_only*/ bool>> root_edges_into_frontier;
};

// Per-layer callback. Captures inbound edges to the current frontier.
jint JNICALL chain_layer_cb(jvmtiHeapReferenceKind reference_kind,
        const jvmtiHeapReferenceInfo* reference_info, jlong /*class_tag*/,
        jlong referrer_class_tag, jlong /*size*/, jlong* tag_ptr,
        jlong* referrer_tag_ptr, jint /*length*/, void* user_data) {
    auto* st = static_cast<ChainBFSState*>(user_data);
    if (!st || !tag_ptr) return JVMTI_VISIT_OBJECTS;
    if (*tag_ptr != st->current_target_tag) return JVMTI_VISIT_OBJECTS;

    bool is_root_edge =
        (reference_kind == JVMTI_HEAP_REFERENCE_JNI_GLOBAL ||
         reference_kind == JVMTI_HEAP_REFERENCE_SYSTEM_CLASS ||
         reference_kind == JVMTI_HEAP_REFERENCE_MONITOR ||
         reference_kind == JVMTI_HEAP_REFERENCE_STACK_LOCAL ||
         reference_kind == JVMTI_HEAP_REFERENCE_JNI_LOCAL ||
         reference_kind == JVMTI_HEAP_REFERENCE_THREAD);

    if (is_root_edge || referrer_tag_ptr == nullptr) {
        // Record a root edge for this frontier node. The actual frontier-node
        // identity is implicit (the current_target_tag matches one
        // resolved-known node from the previous layer).
        ChainBFSState::LayerRecord rec{};
        rec.kind = reference_kind;
        rec.field_index = -1;
        rec.array_index = -1;
        rec.referrer_class_tag = referrer_class_tag;
        st->layer_records.push_back(rec);
        st->root_edges_into_frontier.push_back({reference_kind, true});
        return JVMTI_VISIT_OBJECTS;
    }

    // Non-root edge: tag the referrer if there's still budget.
    if (st->next_record_index >= st->max_records_per_layer) return JVMTI_VISIT_OBJECTS;
    if (*referrer_tag_ptr != 0) return JVMTI_VISIT_OBJECTS;  // already tagged

    jlong band_tag = st->next_tag_base + static_cast<jlong>(st->next_record_index);
    *referrer_tag_ptr = band_tag;

    ChainBFSState::LayerRecord rec{};
    rec.kind = reference_kind;
    rec.field_index = -1;
    rec.array_index = -1;
    rec.referrer_class_tag = referrer_class_tag;
    if (reference_info) {
        if (reference_kind == JVMTI_HEAP_REFERENCE_FIELD ||
            reference_kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD) {
            rec.field_index = reference_info->field.index;
        } else if (reference_kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT) {
            rec.array_index = reference_info->array.index;
        }
    }
    st->layer_records.push_back(rec);
    st->root_edges_into_frontier.push_back({reference_kind, false});
    st->next_record_index++;
    return JVMTI_VISIT_OBJECTS;
}

std::string handle_heap_find_referrer_chain(JNIEnv* jni, const std::string& line,
        bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t ref_pos = find_json_key(line, params_pos, "ref");
    if (ref_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing ref");
    }
    size_t tmp = ref_pos;
    std::string ref_str = read_json_string(line, tmp);
    int ref_id = parse_vobj_ref(ref_str);
    if (ref_id < 0) {
        is_error = true;
        return error_json(-32602, "invalid_params: ref must be vobj#<id>");
    }
    long long max_depth = 0;
    size_t md_pos = find_json_key(line, params_pos, "max_depth");
    if (md_pos != std::string::npos) {
        size_t t2 = md_pos; max_depth = read_json_int(line, t2);
    }
    if (max_depth <= 0) {
        is_error = true;
        return error_json(-32602, "invalid_params: max_depth must be > 0");
    }
    if (max_depth > 10) max_depth = 10;
    long long max_chains = 3;
    size_t mc_pos = find_json_key(line, params_pos, "max_chains");
    if (mc_pos != std::string::npos) {
        size_t t2 = mc_pos; max_chains = read_json_int(line, t2);
    }
    if (max_chains <= 0) max_chains = 3;
    if (max_chains > 10) max_chains = 10;

    jobject target = nullptr;
    if (!resolve_v16_ref(ref_id, target) || !target) {
        is_error = true;
        return error_json(-32011, "unknown_ref",
            "{\"ref\":\"" + json_escape(ref_str) + "\"}");
    }

    // Local node table.
    std::vector<ChainNode> nodes;
    nodes.reserve(32);
    // node 0 = target.
    ChainNode target_node;
    target_node.node_id = 0;
    target_node.vobj_id = ref_id;
    target_node.gref = nullptr;  // we don't own it (the v16 ref pool does)
    {
        jclass tk = jni->GetObjectClass(target);
        target_node.type = resolve_class_sig_for_jclass(jni, tk);
        if (tk) jni->DeleteLocalRef(tk);
    }
    target_node.parent_node_id = -1;
    target_node.depth = 0;
    nodes.push_back(target_node);

    // Frontier: vector of node-ids and their assigned current-layer tag.
    struct Frontier { int node_id; jlong tag; };
    std::vector<Frontier> frontier;
    frontier.push_back({0, g_v16_next_tag.fetch_add(1, std::memory_order_relaxed)});

    // Track which node ids have been terminated (root reached) for chain capture.
    std::vector<int> root_terminated_nodes;

    bool max_depth_reached = false;

    int per_layer_cap = 64;  // hard cap to keep worst case bounded

    for (int depth = 1; depth <= max_depth; ++depth) {
        if (frontier.empty()) break;

        // Tag every node in the frontier with one of the frontier-tags.
        // The simplest scheme is per-node walk: we walk once *per frontier
        // node* so we can attribute layer_records to that node. Cost is
        // O(frontier_size * heap_size); fine for small frontiers (BFS
        // terminates quickly for most allocations).
        std::vector<Frontier> next_frontier;

        for (auto& f : frontier) {
            jobject node_obj = nullptr;
            if (nodes[f.node_id].vobj_id != 0) {
                if (!resolve_v16_ref(nodes[f.node_id].vobj_id, node_obj)) continue;
            } else if (nodes[f.node_id].gref) {
                node_obj = nodes[f.node_id].gref;
            } else {
                continue;
            }
            // Tag it.
            if (g_jvmti->SetTag(node_obj, f.tag) != JVMTI_ERROR_NONE) continue;

            ChainBFSState st;
            st.current_target_tag = f.tag;
            st.next_tag_base = g_v16_next_tag.fetch_add(per_layer_cap + 1,
                std::memory_order_relaxed);
            st.next_record_index = 0;
            st.max_records_per_layer = per_layer_cap;

            jvmtiHeapCallbacks callbacks{};
            callbacks.heap_reference_callback = chain_layer_cb;
            g_jvmti->FollowReferences(0, nullptr, nullptr, &callbacks, &st);

            // Untag this node.
            g_jvmti->SetTag(node_obj, 0);

            // Process records.
            // First, root edges -> mark the parent node terminated.
            for (auto& re : st.root_edges_into_frontier) {
                if (!re.second) continue;  // only root entries
                // The record's index in st.layer_records points to the same
                // index — but root entries don't allocate tags. Match by
                // sequence: every root edge corresponds to ONE element in
                // root_edges_into_frontier whose .second is true.
            }
            // Walk both arrays in lock-step.
            int ri = 0;
            for (size_t i = 0; i < st.root_edges_into_frontier.size(); ++i) {
                bool is_root = st.root_edges_into_frontier[i].second;
                auto kind = st.root_edges_into_frontier[i].first;
                if (is_root) {
                    // Skip duplicates for the same parent (one termination is enough).
                    if (nodes[f.node_id].root_kind.empty()) {
                        nodes[f.node_id].root_kind = heap_ref_kind_to_string(kind);
                        root_terminated_nodes.push_back(f.node_id);
                    }
                    // Don't advance ri (root edges don't consume a layer_record band).
                } else {
                    if (ri >= static_cast<int>(st.layer_records.size())) break;
                }
                // Note: each record (root or non-root) sits in layer_records at
                // index i. Above we only consume the band-allocation for non-roots.
            }

            // Materialize tagged referrer objects layer-by-layer.
            for (int i = 0; i < st.next_record_index; ++i) {
                jlong band_tag = st.next_tag_base + static_cast<jlong>(i);
                jlong tags[1] = {band_tag};
                jint got = 0;
                jobject* obj_arr = nullptr;
                jlong* tag_arr = nullptr;
                if (g_jvmti->GetObjectsWithTags(1, tags, &got, &obj_arr, &tag_arr)
                        != JVMTI_ERROR_NONE) continue;
                if (got > 0 && obj_arr && obj_arr[0]) {
                    jobject ref_obj = obj_arr[0];
                    g_jvmti->SetTag(ref_obj, 0);

                    // Look up the matching LayerRecord for this band.
                    // We need to find the non-root record with band-index i;
                    // i is the count among non-roots, so walk and count.
                    ChainBFSState::LayerRecord rec{};
                    int seen_non_root = 0;
                    for (size_t li = 0; li < st.layer_records.size(); ++li) {
                        bool is_root = st.root_edges_into_frontier[li].second;
                        if (is_root) continue;
                        if (seen_non_root == i) {
                            rec = st.layer_records[li];
                            break;
                        }
                        seen_non_root++;
                    }

                    jclass rk = jni->GetObjectClass(ref_obj);
                    std::string rtype = resolve_class_sig_for_jclass(jni, rk);

                    std::string edge_detail;
                    if (rec.kind == JVMTI_HEAP_REFERENCE_FIELD ||
                        rec.kind == JVMTI_HEAP_REFERENCE_STATIC_FIELD) {
                        edge_detail = resolve_field_name(rk, rec.field_index);
                    } else if (rec.kind == JVMTI_HEAP_REFERENCE_ARRAY_ELEMENT) {
                        char abuf[32];
                        snprintf(abuf, sizeof(abuf), "%d", rec.array_index);
                        edge_detail = abuf;
                    }
                    if (rk) jni->DeleteLocalRef(rk);

                    int new_vobj = mint_v16_ref(jni, ref_obj);
                    jni->DeleteLocalRef(ref_obj);

                    ChainNode new_node;
                    new_node.node_id = static_cast<int>(nodes.size());
                    new_node.vobj_id = new_vobj;
                    new_node.gref = nullptr;
                    new_node.type = rtype;
                    new_node.parent_node_id = f.node_id;
                    new_node.edge_kind = heap_ref_kind_to_string(rec.kind);
                    new_node.edge_detail = edge_detail;
                    new_node.depth = depth;
                    nodes.push_back(new_node);
                    next_frontier.push_back({new_node.node_id,
                        g_v16_next_tag.fetch_add(1, std::memory_order_relaxed)});
                }
                if (obj_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(obj_arr));
                if (tag_arr) g_jvmti->Deallocate(reinterpret_cast<unsigned char*>(tag_arr));
            }
        }

        // If next frontier exists but we've hit max_depth, mark for output.
        if (depth == max_depth && !next_frontier.empty()) {
            max_depth_reached = true;
        }

        // Stop if we already gathered enough rooted chains.
        if (static_cast<int>(root_terminated_nodes.size()) >= max_chains) break;
        if (depth == max_depth) break;

        frontier.swap(next_frontier);
    }

    // Build response. Sort terminated nodes by depth descending (deepest first).
    std::sort(root_terminated_nodes.begin(), root_terminated_nodes.end(),
        [&](int a, int b) { return nodes[a].depth > nodes[b].depth; });
    if (static_cast<int>(root_terminated_nodes.size()) > max_chains) {
        root_terminated_nodes.resize(max_chains);
    }

    std::string chains_json = "[";
    for (size_t ci = 0; ci < root_terminated_nodes.size(); ++ci) {
        if (ci > 0) chains_json += ",";
        int leaf = root_terminated_nodes[ci];
        // Walk from leaf back to target (node_id 0). Path order in the
        // response: leaf-first (root-terminator-first), then up to target.
        std::vector<int> path;
        for (int cur = leaf; cur != -1; cur = nodes[cur].parent_node_id) {
            path.push_back(cur);
        }
        chains_json += "{\"depth\":";
        char buf[32]; snprintf(buf, sizeof(buf), "%d", nodes[leaf].depth);
        chains_json += buf;
        chains_json += ",\"root_kind\":\"" + json_escape(nodes[leaf].root_kind) + "\"";
        chains_json += ",\"path\":[";
        for (size_t pi = 0; pi < path.size(); ++pi) {
            if (pi > 0) chains_json += ",";
            ChainNode& n = nodes[path[pi]];
            chains_json += "{\"ref\":\"vobj#";
            snprintf(buf, sizeof(buf), "%d", n.vobj_id);
            chains_json += buf;
            chains_json += "\",\"type\":\"" + json_escape(n.type) + "\"";
            if (path[pi] != 0) {
                chains_json += ",\"edge\":\"" + json_escape(n.edge_kind) + "\"";
                if (!n.edge_detail.empty()) {
                    chains_json += ",\"edge_detail\":\"" +
                        json_escape(n.edge_detail) + "\"";
                }
            }
            chains_json += "}";
        }
        chains_json += "]}";
    }
    chains_json += "]";

    std::string out = "{\"ref\":\"" + json_escape(ref_str) + "\"";
    out += ",\"chains\":" + chains_json;
    out += max_depth_reached ? ",\"max_depth_reached\":true" :
        ",\"max_depth_reached\":false";
    out += "}";
    is_error = false;
    return out;
}

// ---------------- v1.6 method-trace handlers ----------------

// Throttle check + sample decision. Returns true if event passes both gates.
// `sampled_out` is set to indicate whether this entry/exit pair should emit.
bool throttle_and_sample(MethodTraceSession& session, bool& sampled_out) {
    long long now_ms_v = now_ms();
    long long this_sec_start = now_ms_v - (now_ms_v % 1000);
    if (session.last_throttle_second_ms != this_sec_start) {
        session.last_throttle_second_ms = this_sec_start;
        session.events_this_second = 0;
    }
    if (session.events_this_second >= session.max_events_per_sec) {
        session.dropped_total++;
        session.dropped_since_read++;
        return false;
    }
    if (session.sample_rate < 0.9999) {
        unsigned int seed = static_cast<unsigned int>(now_ms_v ^
            static_cast<long long>(syscall(SYS_gettid)));
        double r = rand_r(&seed) / static_cast<double>(RAND_MAX);
        if (r >= session.sample_rate) {
            sampled_out = false;
            return false;
        }
    }
    session.events_this_second++;
    sampled_out = true;
    return true;
}

bool method_trace_filter_matches(MethodTraceSession& s, const std::string& class_sig,
        const std::string& method_name) {
    if (s.filter_kind == TraceFilterKind::Methods) {
        std::string key = class_sig + method_name;
        return s.method_set.count(key) > 0;
    }
    if (s.filter_kind == TraceFilterKind::ClassPattern) {
        bool cls_ok = false;
        if (s.class_pattern_is_pure_prefix) {
            cls_ok = (s.class_sig_allowlist.count(class_sig) > 0);
        } else if (s.has_class_regex) {
            // Convert class_sig "Lcom/foo/Bar;" to dotted form to match the
            // user's dotted glob (translated to regex).
            std::string dotted;
            if (class_sig.size() >= 2 && class_sig.front() == 'L' &&
                class_sig.back() == ';') {
                dotted = class_sig.substr(1, class_sig.size() - 2);
                for (auto& c : dotted) if (c == '/') c = '.';
            }
            try {
                cls_ok = std::regex_match(dotted, s.class_regex);
            } catch (...) {
                cls_ok = false;
            }
        }
        if (!cls_ok) return false;
        if (s.has_method_regex) {
            try {
                return std::regex_match(method_name, s.method_regex);
            } catch (...) {
                return false;
            }
        }
        return true;
    }
    if (s.filter_kind == TraceFilterKind::MethodRegex) {
        std::string key = class_sig + method_name;
        try {
            return std::regex_search(key, s.method_regex);
        } catch (...) {
            return false;
        }
    }
    return false;
}

void on_method_entry(jvmtiEnv* /*env*/, JNIEnv* jni, jthread thread,
        jmethodID method) {
    PerThreadState* pt = get_or_create_per_thread();
    pt->depth++;
    int current_depth = pt->depth;
    long long now_nano_v = now_nano();

    auto names = resolve_method_name_cached(method);
    if (names.first.empty() && names.second.empty()) return;
    std::string thread_name = resolve_thread_name(thread);

    // Snapshot session list under mutex; iterate without holding it.
    std::vector<std::shared_ptr<MethodTraceSession>> snap;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        for (auto& kv : g_method_traces) snap.push_back(kv.second);
    }

    // Lazily capture args once per method-entry callback if ANY active session
    // wants them — multiple sessions matching the same method share the work.
    bool need_args = false;
    for (auto& sp : snap) {
        if (sp->include_args && sp->kind_entry) { need_args = true; break; }
    }
    std::string shared_args_json;
    bool shared_args_absent = false;
    bool args_captured = false;
    auto ensure_args = [&]() {
        if (args_captured || !need_args) return;
        auto pair = capture_method_entry_args(jni, thread, method);
        shared_args_json = std::move(pair.first);
        shared_args_absent = pair.second;
        args_captured = true;
    };

    for (auto& sp : snap) {
        if (!sp->kind_entry && !sp->kind_exit) continue;
        bool matches = method_trace_filter_matches(*sp, names.first, names.second);

        bool sampled = true;
        if (matches && sp->kind_entry) {
            std::lock_guard<std::mutex> lock(sp->buf_mutex);
            if (!throttle_and_sample(*sp, sampled)) {
                // throttle or sample-out.
                if (sampled) {
                    // Pre-decision was sampled=true but throttle dropped it.
                    // Push 0 onto stack so the matching exit doesn't emit.
                    pt->sampled_stack[sp->buffer_id].push_back(0);
                    pt->entry_nano_stack[sp->buffer_id].push_back(now_nano_v);
                } else {
                    pt->sampled_stack[sp->buffer_id].push_back(0);
                    pt->entry_nano_stack[sp->buffer_id].push_back(now_nano_v);
                }
                continue;
            }
            MethodEvent ev;
            ev.kind = 'E';
            ev.class_sig = names.first;
            ev.method_name = names.second;
            ev.thread_name = thread_name;
            ev.nano_time = now_nano_v;
            ev.depth = current_depth;
            if (sp->include_args) {
                ensure_args();
                ev.args_json = shared_args_json;
                ev.args_absent = shared_args_absent;
            }
            sp->events.push_back(std::move(ev));
            while (sp->events.size() > sp->buffer_cap) {
                sp->events.pop_front();
                sp->dropped_total++;
                sp->dropped_since_read++;
            }
            pt->sampled_stack[sp->buffer_id].push_back(1);
            pt->entry_nano_stack[sp->buffer_id].push_back(now_nano_v);
        } else if (matches && !sp->kind_entry) {
            // Entry not requested but exit is — still need to record sample
            // decision for matching exit. Make a sample decision now.
            std::lock_guard<std::mutex> lock(sp->buf_mutex);
            // Don't push to buffer; just record sampled state.
            bool sample_decision = true;
            if (sp->sample_rate < 0.9999) {
                unsigned int seed = static_cast<unsigned int>(now_nano_v);
                double r = rand_r(&seed) / static_cast<double>(RAND_MAX);
                if (r >= sp->sample_rate) sample_decision = false;
            }
            pt->sampled_stack[sp->buffer_id].push_back(sample_decision ? 1 : 0);
            pt->entry_nano_stack[sp->buffer_id].push_back(now_nano_v);
        } else {
            // Not a match — push 0 so the entry/exit symmetry holds.
            pt->sampled_stack[sp->buffer_id].push_back(0);
            pt->entry_nano_stack[sp->buffer_id].push_back(now_nano_v);
        }
    }
}

void on_method_exit(jvmtiEnv* /*env*/, JNIEnv* jni, jthread thread,
        jmethodID method, jboolean was_popped_by_exception,
        jvalue return_value) {
    PerThreadState* pt = get_or_create_per_thread();
    if (pt->depth > 0) pt->depth--;
    int current_depth = pt->depth + 1;  // exit reports the frame being popped
    long long now_nano_v = now_nano();

    auto names = resolve_method_name_cached(method);
    if (names.first.empty() && names.second.empty()) return;
    std::string thread_name = resolve_thread_name(thread);

    std::vector<std::shared_ptr<MethodTraceSession>> snap;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        for (auto& kv : g_method_traces) snap.push_back(kv.second);
    }

    // Lazily resolve the method's full signature + return-type-char if any
    // active session wants the typed return value. Cached per-jmethodID so
    // subsequent exits of the same method skip the parse.
    bool need_return = false;
    for (auto& sp : snap) {
        if (sp->include_return && sp->kind_exit) { need_return = true; break; }
    }
    std::string sig_full;
    char ret_char = '\0';
    bool sig_resolved = false;
    auto ensure_sig = [&]() {
        if (sig_resolved || !need_return) return;
        auto pair = resolve_method_signature_cached(method);
        sig_full = std::move(pair.first);
        ret_char = pair.second;
        sig_resolved = true;
    };
    std::string shared_return_json;
    bool shared_return_is_void = false;
    bool return_rendered = false;
    auto ensure_return = [&]() {
        if (return_rendered || !need_return) return;
        ensure_sig();
        if (was_popped_by_exception) {
            // Return value is undefined when the frame is popped by an
            // exception (JVMTI spec). Emit null and let the
            // was_popped_by_exception flag carry the signal.
            shared_return_json = "null";
            shared_return_is_void = (ret_char == 'V');
        } else if (ret_char == 'V') {
            shared_return_json = "null";
            shared_return_is_void = true;
        } else {
            shared_return_json = render_return_value(jni, ret_char, sig_full,
                return_value);
            shared_return_is_void = false;
        }
        return_rendered = true;
    };

    for (auto& sp : snap) {
        auto& stk = pt->sampled_stack[sp->buffer_id];
        auto& tstk = pt->entry_nano_stack[sp->buffer_id];
        bool sampled = false;
        long long entry_nano = 0;
        if (!stk.empty()) {
            sampled = (stk.back() != 0);
            stk.pop_back();
        }
        if (!tstk.empty()) {
            entry_nano = tstk.back();
            tstk.pop_back();
        }
        if (!sp->kind_exit) continue;
        if (!sampled) continue;

        // Re-check filter — exit might be in a method that wasn't matched on
        // entry (defensive; symmetry guarantees this matches if we pushed 1).
        std::lock_guard<std::mutex> lock(sp->buf_mutex);
        // Throttle still applies to exit events independently.
        long long now_ms_v = now_ms();
        long long this_sec = now_ms_v - (now_ms_v % 1000);
        if (sp->last_throttle_second_ms != this_sec) {
            sp->last_throttle_second_ms = this_sec;
            sp->events_this_second = 0;
        }
        if (sp->events_this_second >= sp->max_events_per_sec) {
            sp->dropped_total++;
            sp->dropped_since_read++;
            continue;
        }
        sp->events_this_second++;

        MethodEvent ev;
        ev.kind = 'X';
        ev.class_sig = names.first;
        ev.method_name = names.second;
        ev.thread_name = thread_name;
        ev.nano_time = now_nano_v;
        ev.depth = current_depth;
        ev.elapsed_ns = (entry_nano > 0) ? (now_nano_v - entry_nano) : -1;
        ev.was_popped_by_exception = (was_popped_by_exception != JNI_FALSE);
        if (sp->include_return) {
            ensure_return();
            ev.return_json = shared_return_json;
            ev.return_is_void = shared_return_is_void;
        }
        sp->events.push_back(std::move(ev));
        while (sp->events.size() > sp->buffer_cap) {
            sp->events.pop_front();
            sp->dropped_total++;
            sp->dropped_since_read++;
        }
    }
}

void on_vm_object_alloc(jvmtiEnv* /*env*/, JNIEnv* jni, jthread thread,
        jobject /*object*/, jclass object_klass, jlong size) {
    if (!object_klass) return;
    long long now_nano_v = now_nano();
    long long now_ms_v = now_ms();
    std::string thread_name = resolve_thread_name(thread);

    std::vector<std::shared_ptr<AllocTraceSession>> snap;
    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        for (auto& kv : g_alloc_traces) snap.push_back(kv.second);
    }
    if (snap.empty()) return;

    // Cache class signature lookup per call (object_klass is stable for this
    // event but we get repeats across same-class allocations; cache by jclass
    // pointer in a small inline lookup against each session's class_set).
    std::string class_sig;
    bool class_sig_resolved = false;

    for (auto& sp : snap) {
        if (sp->class_set.find(object_klass) == sp->class_set.end()) continue;
        std::lock_guard<std::mutex> lock(sp->buf_mutex);
        // throttle.
        long long this_sec = now_ms_v - (now_ms_v % 1000);
        if (sp->last_throttle_second_ms != this_sec) {
            sp->last_throttle_second_ms = this_sec;
            sp->events_this_second = 0;
        }
        if (sp->events_this_second >= sp->max_events_per_sec) {
            sp->dropped_total++;
            sp->dropped_since_read++;
            continue;
        }
        // sample.
        if (sp->sample_rate < 0.9999) {
            unsigned int seed = static_cast<unsigned int>(now_nano_v);
            double r = rand_r(&seed) / static_cast<double>(RAND_MAX);
            if (r >= sp->sample_rate) continue;
        }
        sp->events_this_second++;
        if (!class_sig_resolved) {
            class_sig = resolve_class_sig_for_jclass(jni, object_klass);
            class_sig_resolved = true;
        }
        AllocEvent ev;
        ev.class_sig = class_sig;
        ev.thread_name = thread_name;
        ev.nano_time = now_nano_v;
        ev.size_bytes = size;
        if (sp->capture_stack_depth > 0) {
            std::vector<jvmtiFrameInfo> frames(sp->capture_stack_depth);
            jint got = 0;
            if (g_jvmti->GetStackTrace(thread, 0, sp->capture_stack_depth,
                    frames.data(), &got) == JVMTI_ERROR_NONE) {
                for (jint i = 0; i < got; ++i) {
                    auto names = resolve_method_name_cached(frames[i].method);
                    jint line_no = -1;
                    jint nlines = 0;
                    jvmtiLineNumberEntry* line_tbl = nullptr;
                    if (g_jvmti->GetLineNumberTable(frames[i].method, &nlines,
                            &line_tbl) == JVMTI_ERROR_NONE && line_tbl) {
                        jint best_line = -1;
                        for (jint li = 0; li < nlines; ++li) {
                            if (line_tbl[li].start_location <= frames[i].location) {
                                best_line = line_tbl[li].line_number;
                            } else {
                                break;
                            }
                        }
                        line_no = best_line;
                    }
                    if (line_tbl) g_jvmti->Deallocate(
                        reinterpret_cast<unsigned char*>(line_tbl));
                    std::string frame_json = "{\"class\":\"" +
                        json_escape(names.first) + "\",\"method\":\"" +
                        json_escape(names.second) + "\",\"line\":";
                    char lbuf[32]; snprintf(lbuf, sizeof(lbuf), "%d", line_no);
                    frame_json += lbuf;
                    frame_json += "}";
                    ev.stack_frames.push_back(frame_json);
                }
            }
        }
        sp->events.push_back(std::move(ev));
        while (sp->events.size() > sp->buffer_cap) {
            sp->events.pop_front();
            sp->dropped_total++;
            sp->dropped_since_read++;
        }
    }
}

// Lazily enable JVMTI event delivery for method-entry / method-exit / alloc.
// Returns false on JVMTI failure.
bool subscribe_method_entry() {
    if (g_method_entry_subscribers.fetch_add(1, std::memory_order_acq_rel) > 0) return true;
    {
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.MethodEntry = on_method_entry;
    }
    if (!refresh_event_callbacks()) {
        g_method_entry_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    if (g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_ENTRY,
            nullptr) != JVMTI_ERROR_NONE) {
        g_method_entry_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    return true;
}
void unsubscribe_method_entry() {
    int prev = g_method_entry_subscribers.fetch_sub(1, std::memory_order_acq_rel);
    if (prev <= 1) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE,
            JVMTI_EVENT_METHOD_ENTRY, nullptr);
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.MethodEntry = nullptr;
        g_jvmti->SetEventCallbacks(&g_event_callbacks, sizeof(g_event_callbacks));
    }
}
bool subscribe_method_exit() {
    if (g_method_exit_subscribers.fetch_add(1, std::memory_order_acq_rel) > 0) return true;
    {
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.MethodExit = on_method_exit;
    }
    if (!refresh_event_callbacks()) {
        g_method_exit_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    if (g_jvmti->SetEventNotificationMode(JVMTI_ENABLE, JVMTI_EVENT_METHOD_EXIT,
            nullptr) != JVMTI_ERROR_NONE) {
        g_method_exit_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    return true;
}
void unsubscribe_method_exit() {
    int prev = g_method_exit_subscribers.fetch_sub(1, std::memory_order_acq_rel);
    if (prev <= 1) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE,
            JVMTI_EVENT_METHOD_EXIT, nullptr);
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.MethodExit = nullptr;
        g_jvmti->SetEventCallbacks(&g_event_callbacks, sizeof(g_event_callbacks));
    }
}
bool subscribe_alloc() {
    if (g_alloc_subscribers.fetch_add(1, std::memory_order_acq_rel) > 0) return true;
    {
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.VMObjectAlloc = on_vm_object_alloc;
    }
    if (!refresh_event_callbacks()) {
        g_alloc_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    if (g_jvmti->SetEventNotificationMode(JVMTI_ENABLE,
            JVMTI_EVENT_VM_OBJECT_ALLOC, nullptr) != JVMTI_ERROR_NONE) {
        g_alloc_subscribers.fetch_sub(1, std::memory_order_acq_rel);
        return false;
    }
    return true;
}
void unsubscribe_alloc() {
    int prev = g_alloc_subscribers.fetch_sub(1, std::memory_order_acq_rel);
    if (prev <= 1) {
        g_jvmti->SetEventNotificationMode(JVMTI_DISABLE,
            JVMTI_EVENT_VM_OBJECT_ALLOC, nullptr);
        std::lock_guard<std::mutex> lock(g_event_cb_mutex);
        g_event_callbacks.VMObjectAlloc = nullptr;
        g_jvmti->SetEventCallbacks(&g_event_callbacks, sizeof(g_event_callbacks));
    }
}

// Translate filter kind string to enum.
TraceFilterKind parse_filter_kind(const std::string& s, bool& ok) {
    ok = true;
    if (s == "methods") return TraceFilterKind::Methods;
    if (s == "class_pattern") return TraceFilterKind::ClassPattern;
    if (s == "method_regex") return TraceFilterKind::MethodRegex;
    ok = false;
    return TraceFilterKind::Methods;
}

std::string handle_method_trace_start(JNIEnv* /*jni*/, const std::string& line,
        bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    std::string filter_kind_str;
    {
        size_t kp = find_json_key(line, params_pos, "filter_kind");
        if (kp == std::string::npos) {
            is_error = true;
            return error_json(-32602, "invalid_params: missing filter_kind");
        }
        size_t tmp = kp;
        filter_kind_str = read_json_string(line, tmp);
    }
    bool kind_ok = false;
    TraceFilterKind kind = parse_filter_kind(filter_kind_str, kind_ok);
    if (!kind_ok) {
        is_error = true;
        return error_json(-32602,
            "invalid_params: filter_kind must be one of methods|class_pattern|method_regex");
    }

    auto sess = std::make_shared<MethodTraceSession>();
    sess->filter_kind = kind;
    sess->buffer_id = mint_buffer_id("mt");
    sess->started_at_ms = now_ms();

    // Optional knobs.
    {
        size_t kp = find_json_key(line, params_pos, "include_args");
        if (kp != std::string::npos) {
            size_t i = kp;
            while (i < line.size() && std::isspace((unsigned char)line[i])) i++;
            if (i + 4 <= line.size() && line.compare(i, 4, "true") == 0) {
                sess->include_args = true;
            }
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "include_return");
        if (kp != std::string::npos) {
            size_t i = kp;
            while (i < line.size() && std::isspace((unsigned char)line[i])) i++;
            if (i + 4 <= line.size() && line.compare(i, 4, "true") == 0) {
                sess->include_return = true;
            }
        }
    }
    // kinds array. Default: both. If present, parse "entry" / "exit".
    {
        size_t arr_pos = find_json_array_start(line, params_pos, "kinds");
        if (arr_pos != std::string::npos) {
            sess->kind_entry = false;
            sess->kind_exit = false;
            iterate_json_array(line, arr_pos,
                [&](size_t s, size_t e) -> bool {
                    std::string el = line.substr(s, e - s);
                    size_t t = 0;
                    std::string v = read_json_string(el, t);
                    if (v == "entry") sess->kind_entry = true;
                    else if (v == "exit") sess->kind_exit = true;
                    return true;
                });
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "max_events_per_sec");
        if (kp != std::string::npos) {
            size_t t = kp; long long v = read_json_int(line, t);
            if (v > 0) sess->max_events_per_sec = static_cast<int>(std::min<long long>(v, 100000));
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "buffer_size");
        if (kp != std::string::npos) {
            size_t t = kp; long long v = read_json_int(line, t);
            if (v > 0) sess->buffer_cap = static_cast<size_t>(std::min<long long>(v, 1000000));
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "sample_rate");
        if (kp != std::string::npos) {
            // Parse as float — JSON has decimal form. The existing read_json_int
            // wouldn't help. Do it inline.
            size_t i = kp;
            while (i < line.size() && std::isspace((unsigned char)line[i])) i++;
            char* end_p = nullptr;
            double v = strtod(line.c_str() + i, &end_p);
            if (end_p && end_p > line.c_str() + i) {
                if (v >= 0.0 && v <= 1.0) sess->sample_rate = v;
            }
        }
    }

    // Compile filter.
    if (kind == TraceFilterKind::Methods) {
        size_t arr_pos = find_json_array_start(line, params_pos, "methods");
        if (arr_pos == std::string::npos) {
            is_error = true;
            return error_json(-32602, "invalid_params: methods array required for filter_kind=methods");
        }
        iterate_json_array(line, arr_pos,
            [&](size_t s, size_t e) -> bool {
                std::string el = line.substr(s, e - s);
                size_t t = 0;
                std::string v = read_json_string(el, t);
                if (!v.empty()) sess->method_set.insert(v);
                return true;
            });
        if (sess->method_set.empty()) {
            is_error = true;
            return error_json(-32602, "invalid_params: methods array is empty");
        }
    } else if (kind == TraceFilterKind::ClassPattern) {
        size_t kp = find_json_key(line, params_pos, "class_pattern");
        if (kp == std::string::npos) {
            is_error = true;
            return error_json(-32602, "invalid_params: missing class_pattern");
        }
        size_t t = kp;
        std::string cp = read_json_string(line, t);
        if (cp.empty()) {
            is_error = true;
            return error_json(-32602, "invalid_params: empty class_pattern");
        }
        if (is_pure_prefix_glob(cp)) {
            sess->class_pattern_is_pure_prefix = true;
            std::string sig_prefix;
            if (cp.size() >= 2 && cp.substr(cp.size() - 2) == ".*") {
                std::string dotted = cp.substr(0, cp.size() - 2);
                // sig_prefix is "L<dotted-with-slashes>/"
                sig_prefix = "L";
                for (char c : dotted) sig_prefix.push_back(c == '.' ? '/' : c);
                sig_prefix.push_back('/');
            } else {
                // Exact class: build full sig and put a single entry.
                sig_prefix = dotted_to_signature_glob(cp);
            }
            if (cp.size() >= 2 && cp.substr(cp.size() - 2) == ".*") {
                sess->class_sig_allowlist =
                    resolve_class_signatures_with_prefix(sig_prefix);
            } else {
                // Exact class.
                sess->class_sig_allowlist.insert(sig_prefix);
            }
            sess->estimated_match_count = static_cast<int>(
                sess->class_sig_allowlist.size());
        } else {
            try {
                sess->class_regex = std::regex(glob_to_regex(cp));
                sess->has_class_regex = true;
            } catch (const std::regex_error&) {
                is_error = true;
                return error_json(-32602, "invalid_params: malformed class_pattern");
            }
        }
        size_t mp = find_json_key(line, params_pos, "method_pattern");
        if (mp != std::string::npos) {
            size_t t2 = mp;
            std::string mpv = read_json_string(line, t2);
            if (!mpv.empty()) {
                try {
                    sess->method_regex = std::regex(glob_to_regex(mpv));
                    sess->has_method_regex = true;
                } catch (const std::regex_error&) {
                    is_error = true;
                    return error_json(-32602,
                        "invalid_params: malformed method_pattern");
                }
            }
        }
    } else if (kind == TraceFilterKind::MethodRegex) {
        size_t kp = find_json_key(line, params_pos, "method_regex");
        if (kp == std::string::npos) {
            is_error = true;
            return error_json(-32602, "invalid_params: missing method_regex");
        }
        size_t t = kp;
        std::string r = read_json_string(line, t);
        try {
            sess->method_regex = std::regex(r);
            sess->has_method_regex = true;
        } catch (const std::regex_error&) {
            is_error = true;
            return error_json(-32602, "invalid_params: malformed method_regex");
        }
    }

    // Register session and enable events.
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        g_method_traces[sess->buffer_id] = sess;
    }
    if (sess->kind_entry) {
        if (!subscribe_method_entry()) {
            std::lock_guard<std::mutex> lock(g_method_traces_mutex);
            g_method_traces.erase(sess->buffer_id);
            is_error = true;
            return error_json(-32020, "jvmti_error",
                "{\"jvmti_error\":-1,\"detail\":\"method_entry_subscribe_failed\"}");
        }
    }
    if (sess->kind_exit) {
        if (!subscribe_method_exit()) {
            if (sess->kind_entry) unsubscribe_method_entry();
            std::lock_guard<std::mutex> lock(g_method_traces_mutex);
            g_method_traces.erase(sess->buffer_id);
            is_error = true;
            return error_json(-32020, "jvmti_error",
                "{\"jvmti_error\":-1,\"detail\":\"method_exit_subscribe_failed\"}");
        }
    }

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(sess->buffer_id) + "\"";
    snprintf(buf, sizeof(buf), ",\"started_at_ms\":%lld", sess->started_at_ms);
    out += buf;
    out += ",\"filter_kind\":\"" + json_escape(filter_kind_str) + "\"";
    snprintf(buf, sizeof(buf), ",\"estimated_match_count\":%d",
        sess->estimated_match_count);
    out += buf;
    out += "}";
    is_error = false;
    return out;
}

std::string serialize_method_event(const MethodEvent& ev) {
    std::string out;
    out += "{\"kind\":\"";
    out += (ev.kind == 'E') ? "entry" : "exit";
    out += "\",\"class\":\"" + json_escape(ev.class_sig) + "\"";
    out += ",\"method\":\"" + json_escape(ev.method_name) + "\"";
    out += ",\"thread\":\"" + json_escape(ev.thread_name) + "\"";
    char buf[64];
    snprintf(buf, sizeof(buf), ",\"nano_time\":%lld", ev.nano_time);
    out += buf;
    snprintf(buf, sizeof(buf), ",\"depth\":%d", ev.depth);
    out += buf;
    if (ev.kind == 'E') {
        out += ",\"args\":";
        out += ev.args_json.empty() ? "null" : ev.args_json;
        if (ev.args_absent) {
            out += ",\"args_absent\":true";
        }
    } else {
        out += ",\"return\":";
        out += ev.return_json.empty() ? "null" : ev.return_json;
        if (ev.return_is_void) {
            out += ",\"void\":true";
        }
        if (ev.was_popped_by_exception) {
            out += ",\"was_popped_by_exception\":true";
        }
        if (ev.elapsed_ns >= 0) {
            snprintf(buf, sizeof(buf), ",\"elapsed_ns\":%lld", ev.elapsed_ns);
            out += buf;
        }
    }
    out += "}";
    return out;
}

std::string serialize_alloc_event(const AllocEvent& ev) {
    std::string out;
    out += "{\"class\":\"" + json_escape(ev.class_sig) + "\"";
    out += ",\"thread\":\"" + json_escape(ev.thread_name) + "\"";
    char buf[64];
    snprintf(buf, sizeof(buf), ",\"nano_time\":%lld", ev.nano_time);
    out += buf;
    snprintf(buf, sizeof(buf), ",\"size_bytes\":%lld", ev.size_bytes);
    out += buf;
    out += ",\"stack\":[";
    for (size_t i = 0; i < ev.stack_frames.size(); ++i) {
        if (i > 0) out += ",";
        out += ev.stack_frames[i];
    }
    out += "]}";
    return out;
}

std::string handle_method_trace_read(const std::string& line, bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t id_pos = find_json_key(line, params_pos, "buffer_id");
    if (id_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing buffer_id");
    }
    size_t tmp = id_pos;
    std::string buf_id = read_json_string(line, tmp);
    long long max_v = 500;
    size_t mp = find_json_key(line, params_pos, "max");
    if (mp != std::string::npos) {
        size_t t = mp; long long v = read_json_int(line, t);
        if (v > 0) max_v = std::min<long long>(v, 5000);
    }

    std::shared_ptr<MethodTraceSession> sess;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        auto it = g_method_traces.find(buf_id);
        if (it != g_method_traces.end()) sess = it->second;
    }
    if (!sess) {
        is_error = true;
        return error_json(-32012, "unknown_buffer_id",
            "{\"buffer_id\":\"" + json_escape(buf_id) + "\"}");
    }

    std::string events_json = "[";
    long long drained = 0;
    long long buffered_after = 0;
    long long dropped_since = 0;
    {
        std::lock_guard<std::mutex> lock(sess->buf_mutex);
        while (drained < max_v && !sess->events.empty()) {
            if (drained > 0) events_json += ",";
            events_json += serialize_method_event(sess->events.front());
            sess->events.pop_front();
            drained++;
        }
        buffered_after = sess->events.size();
        dropped_since = sess->dropped_since_read;
        sess->dropped_since_read = 0;
    }
    events_json += "]";

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(buf_id) + "\"";
    out += ",\"events\":" + events_json;
    snprintf(buf, sizeof(buf), ",\"buffered\":%lld", buffered_after); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_since_last_read\":%lld", dropped_since); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sess->dropped_total); out += buf;
    out += "}";
    is_error = false;
    return out;
}

std::string handle_method_trace_stop(const std::string& line, bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t id_pos = find_json_key(line, params_pos, "buffer_id");
    if (id_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing buffer_id");
    }
    size_t tmp = id_pos;
    std::string buf_id = read_json_string(line, tmp);

    std::shared_ptr<MethodTraceSession> sess;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        auto it = g_method_traces.find(buf_id);
        if (it == g_method_traces.end()) {
            is_error = true;
            return error_json(-32012, "unknown_buffer_id",
                "{\"buffer_id\":\"" + json_escape(buf_id) + "\"}");
        }
        sess = it->second;
        g_method_traces.erase(it);
    }
    if (sess->kind_entry) unsubscribe_method_entry();
    if (sess->kind_exit) unsubscribe_method_exit();

    long long stopped_at_ms = now_ms();
    long long total_events = 0;
    std::string tail_json = "[";
    {
        std::lock_guard<std::mutex> lock(sess->buf_mutex);
        // Drain everything remaining as tail.
        bool first = true;
        while (!sess->events.empty()) {
            if (!first) tail_json += ",";
            tail_json += serialize_method_event(sess->events.front());
            sess->events.pop_front();
            first = false;
            total_events++;
        }
    }
    tail_json += "]";

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(buf_id) + "\"";
    snprintf(buf, sizeof(buf), ",\"stopped_at_ms\":%lld", stopped_at_ms); out += buf;
    snprintf(buf, sizeof(buf), ",\"total_events\":%lld", total_events); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sess->dropped_total); out += buf;
    out += ",\"tail_events\":" + tail_json;
    out += "}";
    is_error = false;
    return out;
}

std::string handle_method_trace_list(const std::string& /*line*/, bool& is_error) {
    std::string traces_json = "[";
    int count = 0;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        for (auto& kv : g_method_traces) {
            auto& sp = kv.second;
            if (count > 0) traces_json += ",";
            const char* fk = "methods";
            if (sp->filter_kind == TraceFilterKind::ClassPattern) fk = "class_pattern";
            else if (sp->filter_kind == TraceFilterKind::MethodRegex) fk = "method_regex";
            long long buffered = 0;
            {
                std::lock_guard<std::mutex> bl(sp->buf_mutex);
                buffered = sp->events.size();
            }
            char buf[64];
            traces_json += "{\"buffer_id\":\"" + json_escape(sp->buffer_id) + "\"";
            traces_json += ",\"filter_kind\":\"";
            traces_json += fk;
            traces_json += "\"";
            snprintf(buf, sizeof(buf), ",\"started_at_ms\":%lld", sp->started_at_ms);
            traces_json += buf;
            snprintf(buf, sizeof(buf), ",\"buffered\":%lld", buffered);
            traces_json += buf;
            snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sp->dropped_total);
            traces_json += buf;
            traces_json += "}";
            count++;
        }
    }
    traces_json += "]";
    char cbuf[32]; snprintf(cbuf, sizeof(cbuf), "%d", count);
    std::string out = "{\"traces\":" + traces_json + ",\"count\":" + cbuf + "}";
    is_error = false;
    return out;
}

// ---------------- v1.6 alloc-trace handlers ----------------

std::string handle_alloc_trace_start(JNIEnv* jni, const std::string& line,
        bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t arr_pos = find_json_array_start(line, params_pos, "class_signatures");
    if (arr_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602,
            "invalid_params: missing class_signatures array");
    }

    auto sess = std::make_shared<AllocTraceSession>();
    sess->buffer_id = mint_buffer_id("at");
    sess->started_at_ms = now_ms();

    std::vector<std::string> unresolved;
    iterate_json_array(line, arr_pos, [&](size_t s, size_t e) -> bool {
        std::string el = line.substr(s, e - s);
        size_t t = 0;
        std::string sig = read_json_string(el, t);
        if (sig.empty()) return true;
        auto matches = find_all_loaded_classes_by_signature(jni, sig);
        if (matches.empty()) {
            unresolved.push_back(sig);
            return true;
        }
        for (jclass jc : matches) {
            jclass gref = static_cast<jclass>(jni->NewGlobalRef(jc));
            jni->DeleteLocalRef(jc);
            if (gref) {
                sess->class_global_refs.push_back(gref);
                sess->class_set.insert(gref);
            }
        }
        return true;
    });

    {
        size_t kp = find_json_key(line, params_pos, "capture_stack_depth");
        if (kp != std::string::npos) {
            size_t t = kp; long long v = read_json_int(line, t);
            if (v > 0) sess->capture_stack_depth = static_cast<int>(std::min<long long>(v, 64));
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "max_events_per_sec");
        if (kp != std::string::npos) {
            size_t t = kp; long long v = read_json_int(line, t);
            if (v > 0) sess->max_events_per_sec = static_cast<int>(std::min<long long>(v, 100000));
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "buffer_size");
        if (kp != std::string::npos) {
            size_t t = kp; long long v = read_json_int(line, t);
            if (v > 0) sess->buffer_cap = static_cast<size_t>(std::min<long long>(v, 1000000));
        }
    }
    {
        size_t kp = find_json_key(line, params_pos, "sample_rate");
        if (kp != std::string::npos) {
            size_t i = kp;
            while (i < line.size() && std::isspace((unsigned char)line[i])) i++;
            char* end_p = nullptr;
            double v = strtod(line.c_str() + i, &end_p);
            if (end_p && end_p > line.c_str() + i) {
                if (v >= 0.0 && v <= 1.0) sess->sample_rate = v;
            }
        }
    }

    if (sess->class_global_refs.empty()) {
        is_error = true;
        return error_json(-32013, "no_classes_loaded",
            "{\"hint\":\"none of the requested class_signatures matched a loaded class\"}");
    }

    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        g_alloc_traces[sess->buffer_id] = sess;
    }
    if (!subscribe_alloc()) {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        g_alloc_traces.erase(sess->buffer_id);
        for (jclass gr : sess->class_global_refs) jni->DeleteGlobalRef(gr);
        is_error = true;
        return error_json(-32020, "jvmti_error",
            "{\"jvmti_error\":-1,\"detail\":\"alloc_subscribe_failed\"}");
    }

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(sess->buffer_id) + "\"";
    snprintf(buf, sizeof(buf), ",\"started_at_ms\":%lld", sess->started_at_ms);
    out += buf;
    snprintf(buf, sizeof(buf), ",\"resolved_classes\":%zu",
        sess->class_global_refs.size());
    out += buf;
    out += ",\"unresolved_classes\":[";
    for (size_t i = 0; i < unresolved.size(); ++i) {
        if (i > 0) out += ",";
        out += "\"" + json_escape(unresolved[i]) + "\"";
    }
    out += "]}";
    is_error = false;
    return out;
}

std::string handle_alloc_trace_read(const std::string& line, bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t id_pos = find_json_key(line, params_pos, "buffer_id");
    if (id_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing buffer_id");
    }
    size_t tmp = id_pos;
    std::string buf_id = read_json_string(line, tmp);
    long long max_v = 500;
    size_t mp = find_json_key(line, params_pos, "max");
    if (mp != std::string::npos) {
        size_t t = mp; long long v = read_json_int(line, t);
        if (v > 0) max_v = std::min<long long>(v, 5000);
    }

    std::shared_ptr<AllocTraceSession> sess;
    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        auto it = g_alloc_traces.find(buf_id);
        if (it != g_alloc_traces.end()) sess = it->second;
    }
    if (!sess) {
        is_error = true;
        return error_json(-32012, "unknown_buffer_id",
            "{\"buffer_id\":\"" + json_escape(buf_id) + "\"}");
    }

    std::string events_json = "[";
    long long drained = 0;
    long long buffered_after = 0;
    long long dropped_since = 0;
    {
        std::lock_guard<std::mutex> lock(sess->buf_mutex);
        while (drained < max_v && !sess->events.empty()) {
            if (drained > 0) events_json += ",";
            events_json += serialize_alloc_event(sess->events.front());
            sess->events.pop_front();
            drained++;
        }
        buffered_after = sess->events.size();
        dropped_since = sess->dropped_since_read;
        sess->dropped_since_read = 0;
    }
    events_json += "]";

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(buf_id) + "\"";
    out += ",\"events\":" + events_json;
    snprintf(buf, sizeof(buf), ",\"buffered\":%lld", buffered_after); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_since_last_read\":%lld", dropped_since); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sess->dropped_total); out += buf;
    out += "}";
    is_error = false;
    return out;
}

std::string handle_alloc_trace_stop(JNIEnv* jni, const std::string& line,
        bool& is_error) {
    size_t params_pos = find_json_key(line, 0, "params");
    if (params_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing params");
    }
    size_t id_pos = find_json_key(line, params_pos, "buffer_id");
    if (id_pos == std::string::npos) {
        is_error = true;
        return error_json(-32602, "invalid_params: missing buffer_id");
    }
    size_t tmp = id_pos;
    std::string buf_id = read_json_string(line, tmp);

    std::shared_ptr<AllocTraceSession> sess;
    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        auto it = g_alloc_traces.find(buf_id);
        if (it == g_alloc_traces.end()) {
            is_error = true;
            return error_json(-32012, "unknown_buffer_id",
                "{\"buffer_id\":\"" + json_escape(buf_id) + "\"}");
        }
        sess = it->second;
        g_alloc_traces.erase(it);
    }
    unsubscribe_alloc();

    long long stopped_at_ms = now_ms();
    long long total_events = 0;
    std::string tail_json = "[";
    {
        std::lock_guard<std::mutex> lock(sess->buf_mutex);
        bool first = true;
        while (!sess->events.empty()) {
            if (!first) tail_json += ",";
            tail_json += serialize_alloc_event(sess->events.front());
            sess->events.pop_front();
            first = false;
            total_events++;
        }
    }
    tail_json += "]";

    for (jclass gr : sess->class_global_refs) {
        if (gr) jni->DeleteGlobalRef(gr);
    }

    char buf[64];
    std::string out = "{\"buffer_id\":\"" + json_escape(buf_id) + "\"";
    snprintf(buf, sizeof(buf), ",\"stopped_at_ms\":%lld", stopped_at_ms); out += buf;
    snprintf(buf, sizeof(buf), ",\"total_events\":%lld", total_events); out += buf;
    snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sess->dropped_total); out += buf;
    out += ",\"tail_events\":" + tail_json;
    out += "}";
    is_error = false;
    return out;
}

std::string handle_alloc_trace_list(const std::string& /*line*/, bool& is_error) {
    std::string traces_json = "[";
    int count = 0;
    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        for (auto& kv : g_alloc_traces) {
            auto& sp = kv.second;
            if (count > 0) traces_json += ",";
            long long buffered = 0;
            {
                std::lock_guard<std::mutex> bl(sp->buf_mutex);
                buffered = sp->events.size();
            }
            char buf[64];
            traces_json += "{\"buffer_id\":\"" + json_escape(sp->buffer_id) + "\"";
            snprintf(buf, sizeof(buf), ",\"started_at_ms\":%lld", sp->started_at_ms);
            traces_json += buf;
            snprintf(buf, sizeof(buf), ",\"buffered\":%lld", buffered);
            traces_json += buf;
            snprintf(buf, sizeof(buf), ",\"dropped_total\":%lld", sp->dropped_total);
            traces_json += buf;
            snprintf(buf, sizeof(buf), ",\"resolved_classes\":%zu",
                sp->class_global_refs.size());
            traces_json += buf;
            traces_json += "}";
            count++;
        }
    }
    traces_json += "]";
    char cbuf[32]; snprintf(cbuf, sizeof(cbuf), "%d", count);
    std::string out = "{\"traces\":" + traces_json + ",\"count\":" + cbuf + "}";
    is_error = false;
    return out;
}

// Stop every trace session, freeing globals. Used by stop_all_traces RPC
// and by the client-disconnect cleanup. Returns (method_count, alloc_count).
std::pair<int, int> stop_all_traces_internal() {
    int method_stopped = 0;
    int alloc_stopped = 0;
    std::vector<std::shared_ptr<MethodTraceSession>> method_snap;
    std::vector<std::shared_ptr<AllocTraceSession>> alloc_snap;
    {
        std::lock_guard<std::mutex> lock(g_method_traces_mutex);
        for (auto& kv : g_method_traces) method_snap.push_back(kv.second);
        g_method_traces.clear();
    }
    {
        std::lock_guard<std::mutex> lock(g_alloc_traces_mutex);
        for (auto& kv : g_alloc_traces) alloc_snap.push_back(kv.second);
        g_alloc_traces.clear();
    }
    for (auto& sp : method_snap) {
        if (sp->kind_entry) unsubscribe_method_entry();
        if (sp->kind_exit) unsubscribe_method_exit();
        method_stopped++;
    }
    // For alloc sessions, free their class global refs. We need a JNIEnv but
    // can't always assume one here; the disconnect path is the only caller
    // that needs to free them, so it provides its own jni and re-frees on
    // disconnect (the gref leak window is bounded by detach).
    for (auto& sp : alloc_snap) {
        (void)sp;
        unsubscribe_alloc();
        alloc_stopped++;
    }
    // Also clear per-thread state so the next session starts clean.
    {
        std::lock_guard<std::mutex> lock(g_per_thread_mutex);
        g_per_thread_states.clear();
    }
    return {method_stopped, alloc_stopped};
}

std::string handle_stop_all_traces(const std::string& /*line*/, bool& is_error) {
    auto counts = stop_all_traces_internal();
    char buf[64];
    std::string out = "{";
    snprintf(buf, sizeof(buf), "\"stopped_method_traces\":%d,", counts.first);
    out += buf;
    snprintf(buf, sizeof(buf), "\"stopped_alloc_traces\":%d", counts.second);
    out += buf;
    out += "}";
    is_error = false;
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
        } else if (method == "agent.heap_count_instances") {
            bool is_err = false;
            std::string body = handle_heap_count_instances(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.heap_iterate_by_class") {
            bool is_err = false;
            std::string body = handle_heap_iterate_by_class(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.heap_find_referrers") {
            bool is_err = false;
            std::string body = handle_heap_find_referrers(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.heap_find_referrer_chain") {
            bool is_err = false;
            std::string body = handle_heap_find_referrer_chain(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.method_trace_start") {
            bool is_err = false;
            std::string body = handle_method_trace_start(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.method_trace_read") {
            bool is_err = false;
            std::string body = handle_method_trace_read(line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.method_trace_stop") {
            bool is_err = false;
            std::string body = handle_method_trace_stop(line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.method_trace_list") {
            bool is_err = false;
            std::string body = handle_method_trace_list(line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.alloc_trace_start") {
            bool is_err = false;
            std::string body = handle_alloc_trace_start(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.alloc_trace_read") {
            bool is_err = false;
            std::string body = handle_alloc_trace_read(line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.alloc_trace_stop") {
            bool is_err = false;
            std::string body = handle_alloc_trace_stop(jni, line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.alloc_trace_list") {
            bool is_err = false;
            std::string body = handle_alloc_trace_list(line, is_err);
            send_response(fd, id, body, is_err);
        } else if (method == "agent.stop_all_traces") {
            bool is_err = false;
            std::string body = handle_stop_all_traces(line, is_err);
            send_response(fd, id, body, is_err);
        } else {
            send_response(fd, id, error_json(-32601, "method not found"), true);
        }
    }

    // Client disconnected (read_line returned false). Reap any v1.6 state
    // tied to this session: trace callbacks, vobj#<id> refs, per-thread
    // bookkeeping. Without this a client crash would leave event callbacks
    // chewing CPU until the next session starts (or forever).
    stop_all_traces_internal();
    if (jni) release_v16_refs(jni);
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
    //
    // v1.6: route the callback through the shared g_event_callbacks struct so
    // method-entry / method-exit / vm-object-alloc callbacks (added later by
    // trace sessions) can coexist. SetEventCallbacks overwrites the whole
    // struct, so every callback we want active has to be set together.
    {
        {
            std::lock_guard<std::mutex> lock(g_event_cb_mutex);
            g_event_callbacks.ClassFileLoadHook = on_class_file_load_hook;
        }
        if (!refresh_event_callbacks()) {
            LOGE("SetEventCallbacks failed — revert support disabled");
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
