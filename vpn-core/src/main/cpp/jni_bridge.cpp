/**
 * BobrDobr VPN Mobile Client - JNI Bridge Implementation
 * Project: БобрДобр VPN ("Центр гидробиологических инноваций")
 *
 * Implements JNI bindings between Kotlin VpnTunnelService/NativeEngineController
 * and native C-ABI protocol engines (libengine_rust.so and libengine_go.so).
 *
 * Hardened against memory corruption, use-after-free, deadlocks, and dynamic loader crashes:
 *   - RTLD_LOCAL used for engine isolation.
 *   - Engine handles cached in memory (NO dlclose to prevent Go runtime SIGSEGV).
 *   - Thread-safe callbacks with RAII ScopedJniEnv and NewLocalRef protection.
 *   - Full support for 2-phase dynamic TUN negotiation via start_tunnel_v2.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <mutex>
#include <unordered_map>
#include <cstring>
#include <atomic>
#include "include/engine_api.h"

#define LOG_TAG "BobrDobr_JNI"
static std::atomic_bool g_logging_enabled{true};
#define LOGD(...) do { if (g_logging_enabled.load()) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGI(...) do { if (g_logging_enabled.load()) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGW(...) do { if (g_logging_enabled.load()) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); } while (0)
#define LOGE(...) do { if (g_logging_enabled.load()) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); } while (0)

namespace {

// Global Java VM reference
JavaVM* g_jvm = nullptr;

// Synchronization
std::mutex g_engine_mutex;

// Function pointer signatures matching engine_api.h
typedef int (*fn_start_tunnel)(int, const char*, protect_callback_fn, status_callback_fn);
typedef int (*fn_start_tunnel_v2)(int, const char*, protect_callback_fn, status_callback_fn, tun_config_callback_fn);
typedef int (*fn_stop_tunnel)(void);
typedef int (*fn_update_network)(int64_t);
typedef int (*fn_get_tunnel_stats)(tunnel_stats_t*);
typedef void (*fn_set_logging_enabled)(bool);

struct EngineTable {
    void* handle = nullptr;
    fn_start_tunnel start_tunnel = nullptr;
    fn_start_tunnel_v2 start_tunnel_v2 = nullptr;
    fn_stop_tunnel stop_tunnel = nullptr;
    fn_update_network update_network = nullptr;
    fn_get_tunnel_stats get_tunnel_stats = nullptr;
    fn_set_logging_enabled set_logging_enabled = nullptr;
};

// Global cache for loaded engine libraries (Handles are NEVER dlclose'd to prevent Go runtime SIGSEGV)
std::unordered_map<std::string, EngineTable> g_engine_cache;
EngineTable g_active_engine;

// Java Callback references (GlobalRefs managed with proper synchronization)
jobject g_protector_obj = nullptr;
jmethodID g_protect_method_id = nullptr;

jobject g_status_listener_obj = nullptr;
jmethodID g_status_method_id = nullptr;

jobject g_tun_config_cb_obj = nullptr;
jmethodID g_tun_config_method_id = nullptr;

// Cached TunnelStats class and constructor
jclass g_tunnel_stats_class = nullptr;
jmethodID g_tunnel_stats_ctor = nullptr;

// RAII helper for JVM thread attachment
class ScopedJniEnv {
public:
    ScopedJniEnv() : env_(nullptr), attached_(false) {
        if (!g_jvm) return;
        jint res = g_jvm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        }
    }

    ~ScopedJniEnv() {
        if (attached_ && g_jvm) {
            g_jvm->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env_; }
    bool isValid() const { return env_ != nullptr; }

private:
    JNIEnv* env_;
    bool attached_;
};

// C-ABI Callback: Socket protection (VpnService.protect)
bool c_protect_callback(int socket_fd) {
    ScopedJniEnv scopedEnv;
    JNIEnv* env = scopedEnv.get();
    if (!env) {
        LOGE("c_protect_callback: Failed to acquire JNIEnv");
        return false;
    }

    jobject local_protector = nullptr;
    jmethodID protect_method = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_protector_obj && g_protect_method_id) {
            local_protector = env->NewLocalRef(g_protector_obj);
            protect_method = g_protect_method_id;
        }
    }

    if (!local_protector || !protect_method) {
        LOGE("c_protect_callback: SocketProtector instance is null");
        if (local_protector) env->DeleteLocalRef(local_protector);
        return false;
    }

    // Call Java method OUTSIDE g_engine_mutex lock to prevent deadlock
    jboolean result = env->CallBooleanMethod(local_protector, protect_method, static_cast<jint>(socket_fd));
    env->DeleteLocalRef(local_protector);

    if (env->ExceptionCheck()) {
        LOGE("c_protect_callback: Exception in Java protectSocket()");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    LOGD("c_protect_callback: protect(fd=%d) returned %s", socket_fd, result ? "true" : "false");
    return result == JNI_TRUE;
}

// C-ABI Callback: Status and state transition reporting
void c_status_callback(int status_code, const char* message) {
    ScopedJniEnv scopedEnv;
    JNIEnv* env = scopedEnv.get();
    if (!env) {
        LOGE("c_status_callback: Failed to acquire JNIEnv");
        return;
    }

    jobject local_listener = nullptr;
    jmethodID status_method = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_status_listener_obj && g_status_method_id) {
            local_listener = env->NewLocalRef(g_status_listener_obj);
            status_method = g_status_method_id;
        }
    }

    if (!local_listener || !status_method) {
        LOGD("c_status_callback: No status listener registered (code=%d, msg=%s)",
             status_code, message ? message : "");
        if (local_listener) env->DeleteLocalRef(local_listener);
        return;
    }

    jstring j_msg = env->NewStringUTF(message ? message : "");
    env->CallVoidMethod(local_listener, status_method, static_cast<jint>(status_code), j_msg);
    env->DeleteLocalRef(j_msg);
    env->DeleteLocalRef(local_listener);

    if (env->ExceptionCheck()) {
        LOGE("c_status_callback: Exception in Java onStatusChanged()");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

// C-ABI Callback: Dynamic 2-phase TUN creation (CSTP negotiated parameters -> VpnService.Builder)
int c_tun_config_callback(const char* json_str) {
    ScopedJniEnv scopedEnv;
    JNIEnv* env = scopedEnv.get();
    if (!env) {
        LOGE("c_tun_config_callback: Failed to acquire JNIEnv");
        return -1;
    }

    jobject local_cb = nullptr;
    jmethodID method = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_tun_config_cb_obj && g_tun_config_method_id) {
            local_cb = env->NewLocalRef(g_tun_config_cb_obj);
            method = g_tun_config_method_id;
        }
    }

    if (!local_cb || !method) {
        LOGE("c_tun_config_callback: TunConfigCallback instance is null");
        if (local_cb) env->DeleteLocalRef(local_cb);
        return -1;
    }

    jstring j_json = env->NewStringUTF(json_str ? json_str : "{}");
    jint tunFd = env->CallIntMethod(local_cb, method, j_json);
    env->DeleteLocalRef(j_json);
    env->DeleteLocalRef(local_cb);

    if (env->ExceptionCheck()) {
        LOGE("c_tun_config_callback: Exception in Java onTunConfigReady()");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return -1;
    }

    LOGI("c_tun_config_callback: Dynamic TUN created successfully, fd=%d", tunFd);
    return static_cast<int>(tunFd);
}

void cleanup_callbacks_locked(JNIEnv* env) {
    if (g_protector_obj) {
        env->DeleteGlobalRef(g_protector_obj);
        g_protector_obj = nullptr;
        g_protect_method_id = nullptr;
    }
    if (g_status_listener_obj) {
        env->DeleteGlobalRef(g_status_listener_obj);
        g_status_listener_obj = nullptr;
        g_status_method_id = nullptr;
    }
    if (g_tun_config_cb_obj) {
        env->DeleteGlobalRef(g_tun_config_cb_obj);
        g_tun_config_cb_obj = nullptr;
        g_tun_config_method_id = nullptr;
    }
}

} // namespace

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved;
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOGE("JNI_OnLoad: Failed to get JNIEnv");
        return JNI_ERR;
    }

    LOGI("BobrDobr VPN JNI Bridge loaded successfully");

    // Cache TunnelStats class and constructor
    jclass localStatsClass = env->FindClass("org/bobrdobr/vpn/core/model/TunnelStats");
    if (!localStatsClass) {
        LOGE("JNI_OnLoad: Could not find TunnelStats class");
        return JNI_ERR;
    }
    g_tunnel_stats_class = reinterpret_cast<jclass>(env->NewGlobalRef(localStatsClass));
    env->DeleteLocalRef(localStatsClass);

    g_tunnel_stats_ctor = env->GetMethodID(g_tunnel_stats_class, "<init>", "(JJJJJJJJJ)V");
    if (!g_tunnel_stats_ctor) {
        LOGE("JNI_OnLoad: Could not find TunnelStats constructor (JJJJJJJJJ)V");
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* vm, void* reserved) {
    (void)reserved;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        if (g_tunnel_stats_class) {
            env->DeleteGlobalRef(g_tunnel_stats_class);
            g_tunnel_stats_class = nullptr;
        }
        cleanup_callbacks_locked(env);
    }
    // Note: Do NOT dlclose cached engine handles here; OS process teardown will free pages cleanly
    // without triggering SIGSEGV in background Go runtime runtime/cgo worker threads.
}

/**
 * Initializes and dynamically binds the selected native protocol engine.
 * Cached in g_engine_cache to prevent dlclose-induced crashes when toggling engines.
 */
JNIEXPORT jint JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeInitEngine(
        JNIEnv* env,
        jclass clazz,
        jstring jLibName) {
    (void)clazz;
    std::lock_guard<std::mutex> lock(g_engine_mutex);

    const char* libName = env->GetStringUTFChars(jLibName, nullptr);
    if (!libName) {
        return ENGINE_ERR_INVALID_ARG;
    }

    std::string libNameStr(libName);
    env->ReleaseStringUTFChars(jLibName, libName);

    // Check if library handle is already cached
    auto it = g_engine_cache.find(libNameStr);
    if (it != g_engine_cache.end()) {
        g_active_engine = it->second;
        if (g_active_engine.set_logging_enabled) {
            g_active_engine.set_logging_enabled(g_logging_enabled.load());
        }
        LOGI("Native engine '%s' restored from cache (handle=%p)", libNameStr.c_str(), g_active_engine.handle);
        return ENGINE_SUCCESS;
    }

    LOGI("Loading native VPN engine library: %s (RTLD_NOW | RTLD_LOCAL)", libNameStr.c_str());

    // Use RTLD_LOCAL to keep symbol tables isolated
    void* handle = dlopen(libNameStr.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        const char* err = dlerror();
        LOGE("Failed to dlopen engine '%s': %s", libNameStr.c_str(), err ? err : "unknown error");
        return ENGINE_ERR_GENERIC;
    }

    EngineTable table;
    table.handle = handle;
    table.start_tunnel = reinterpret_cast<fn_start_tunnel>(dlsym(handle, "start_tunnel"));
    table.start_tunnel_v2 = reinterpret_cast<fn_start_tunnel_v2>(dlsym(handle, "start_tunnel_v2"));
    table.stop_tunnel = reinterpret_cast<fn_stop_tunnel>(dlsym(handle, "stop_tunnel"));
    table.update_network = reinterpret_cast<fn_update_network>(dlsym(handle, "update_network"));
    table.get_tunnel_stats = reinterpret_cast<fn_get_tunnel_stats>(dlsym(handle, "get_tunnel_stats"));
    table.set_logging_enabled = reinterpret_cast<fn_set_logging_enabled>(dlsym(handle, "set_logging_enabled"));

    if ((!table.start_tunnel && !table.start_tunnel_v2) ||
        !table.stop_tunnel || !table.update_network || !table.get_tunnel_stats) {
        LOGE("Engine library '%s' missing required C-ABI symbols! start=%p, start_v2=%p, stop=%p, update=%p, stats=%p",
             libNameStr.c_str(), table.start_tunnel, table.start_tunnel_v2,
             table.stop_tunnel, table.update_network, table.get_tunnel_stats);
        // Do not dlclose, keep loaded safely
        return ENGINE_ERR_GENERIC;
    }

    g_engine_cache[libNameStr] = table;
    g_active_engine = table;
    if (g_active_engine.set_logging_enabled) {
        g_active_engine.set_logging_enabled(g_logging_enabled.load());
    }
    LOGI("Native engine '%s' loaded and cached successfully (has_start_v2=%d)",
         libNameStr.c_str(), table.start_tunnel_v2 != nullptr);
    return ENGINE_SUCCESS;
}

/**
 * Starts the tunnel via the active native engine.
 * Supports both traditional 1-phase TUN (start_tunnel) and 2-phase dynamic TUN (start_tunnel_v2).
 */
JNIEXPORT jint JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeStartTunnel(
        JNIEnv* env,
        jclass clazz,
        jint tunFd,
        jstring jConfigJson,
        jobject jProtector,
        jobject jStatusListener,
        jobject jTunConfigCallback) {
    (void)clazz;

    fn_start_tunnel start_fn = nullptr;
    fn_start_tunnel_v2 start_v2_fn = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);

        if (!g_active_engine.start_tunnel && !g_active_engine.start_tunnel_v2) {
            LOGE("nativeStartTunnel called but no engine loaded");
            return ENGINE_ERR_NOT_RUNNING;
        }

        // tunFd can be -1 if dynamic 2-phase creation callback is supplied
        if ((tunFd < 0 && !jTunConfigCallback) || !jConfigJson || !jProtector) {
            LOGE("nativeStartTunnel invalid arguments: tunFd=%d, hasConfigCb=%d",
                 tunFd, jTunConfigCallback != nullptr);
            return ENGINE_ERR_INVALID_ARG;
        }

        // Release old global references if present
        cleanup_callbacks_locked(env);

        // Register protector
        g_protector_obj = env->NewGlobalRef(jProtector);
        jclass protectorClass = env->GetObjectClass(jProtector);
        g_protect_method_id = env->GetMethodID(protectorClass, "protectSocket", "(I)Z");
        env->DeleteLocalRef(protectorClass);

        if (!g_protect_method_id) {
            LOGE("Could not find protectSocket(I)Z method in SocketProtector");
            cleanup_callbacks_locked(env);
            return ENGINE_ERR_PROTECT_FAILED;
        }

        // Register status listener if provided
        if (jStatusListener) {
            g_status_listener_obj = env->NewGlobalRef(jStatusListener);
            jclass listenerClass = env->GetObjectClass(jStatusListener);
            g_status_method_id = env->GetMethodID(listenerClass, "onStatusChanged", "(ILjava/lang/String;)V");
            env->DeleteLocalRef(listenerClass);
        }

        // Register dynamic TUN configuration callback if provided
        if (jTunConfigCallback) {
            g_tun_config_cb_obj = env->NewGlobalRef(jTunConfigCallback);
            jclass tunCbClass = env->GetObjectClass(jTunConfigCallback);
            g_tun_config_method_id = env->GetMethodID(tunCbClass, "onTunConfigReady", "(Ljava/lang/String;)I");
            env->DeleteLocalRef(tunCbClass);
        }

        start_fn = g_active_engine.start_tunnel;
        start_v2_fn = g_active_engine.start_tunnel_v2;
    }

    const char* configJson = env->GetStringUTFChars(jConfigJson, nullptr);
    LOGI("Starting native tunnel (tunFd=%d, hasV2=%d, hasTunCb=%d)...",
         tunFd, start_v2_fn != nullptr, jTunConfigCallback != nullptr);

    int res = ENGINE_SUCCESS;
    if (start_v2_fn && jTunConfigCallback) {
        res = start_v2_fn(tunFd, configJson, c_protect_callback, c_status_callback, c_tun_config_callback);
    } else if (start_fn) {
        res = start_fn(tunFd, configJson, c_protect_callback, c_status_callback);
    } else if (start_v2_fn) {
        res = start_v2_fn(tunFd, configJson, c_protect_callback, c_status_callback, nullptr);
    } else {
        res = ENGINE_ERR_NOT_RUNNING;
    }

    env->ReleaseStringUTFChars(jConfigJson, configJson);
    return res;
}

/**
 * Gracefully stops the active native tunnel.
 */
JNIEXPORT jint JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeStopTunnel(
        JNIEnv* env,
        jclass clazz) {
    (void)clazz;

    fn_stop_tunnel stop_fn = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        stop_fn = g_active_engine.stop_tunnel;
    }

    int res = ENGINE_SUCCESS;
    if (stop_fn) {
        LOGI("Stopping native tunnel...");
        res = stop_fn();
    }

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        cleanup_callbacks_locked(env);
    }

    return res;
}

/**
 * Updates the underlying physical network handle.
 */
JNIEXPORT jint JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeUpdateNetwork(
        JNIEnv* env,
        jclass clazz,
        jlong netId) {
    (void)clazz;

    fn_update_network update_fn = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        update_fn = g_active_engine.update_network;
    }

    if (!update_fn) {
        return ENGINE_ERR_NOT_RUNNING;
    }

    LOGI("Updating native engine network handle to %lld", static_cast<long long>(netId));
    return update_fn(static_cast<int64_t>(netId));
}

/**
 * Queries current tunnel metrics and returns a Kotlin TunnelStats instance.
 */
JNIEXPORT jobject JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeGetTunnelStats(
        JNIEnv* env,
        jclass clazz) {
    (void)clazz;

    fn_get_tunnel_stats stats_fn = nullptr;
    jclass stats_class = nullptr;
    jmethodID stats_ctor = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        stats_fn = g_active_engine.get_tunnel_stats;
        stats_class = g_tunnel_stats_class;
        stats_ctor = g_tunnel_stats_ctor;
    }

    if (!stats_fn || !stats_class || !stats_ctor) {
        return nullptr;
    }

    tunnel_stats_t stats;
    std::memset(&stats, 0, sizeof(stats));

    int res = stats_fn(&stats);
    if (res != ENGINE_SUCCESS) {
        return nullptr;
    }

    jobject statsObj = env->NewObject(
            stats_class,
            stats_ctor,
            static_cast<jlong>(stats.rx_bytes),
            static_cast<jlong>(stats.tx_bytes),
            static_cast<jlong>(stats.rx_packets),
            static_cast<jlong>(stats.tx_packets),
            static_cast<jlong>(stats.rx_speed_bps / 8),
            static_cast<jlong>(stats.tx_speed_bps / 8),
            static_cast<jlong>(stats.ping_ms),
            static_cast<jlong>(stats.last_handshake_timestamp),
            static_cast<jlong>(stats.timestamp)
    );

    return statsObj;
}

JNIEXPORT void JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeSetLoggingEnabled(
        JNIEnv* env,
        jclass clazz,
        jboolean enabled) {
    (void)env;
    (void)clazz;
    g_logging_enabled.store(enabled == JNI_TRUE);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (g_active_engine.set_logging_enabled) {
        g_active_engine.set_logging_enabled(enabled == JNI_TRUE);
    }
}

} // extern "C"
