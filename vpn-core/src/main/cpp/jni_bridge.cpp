/**
 * BobrDobr VPN Mobile Client - JNI Bridge Implementation
 * Project: БобрДобр VPN ("Центр гидробиологических инноваций")
 *
 * Implements JNI bindings between Kotlin VpnTunnelService/NativeEngineController
 * and native C-ABI protocol engines (libengine_rust.so and libengine_go.so).
 *
 * Designed to be leak-free and deadlock-free with thread-safe callbacks
 * and fine-grained state synchronization.
 */

#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <string>
#include <mutex>
#include <cstring>
#include "include/engine_api.h"

#define LOG_TAG "BobrDobr_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Global Java VM reference
JavaVM* g_jvm = nullptr;

// Synchronization
std::mutex g_engine_mutex;

// Function pointers to active native engine
typedef int (*fn_start_tunnel)(int, const char*, protect_callback_fn, status_callback_fn);
typedef int (*fn_stop_tunnel)(void);
typedef int (*fn_update_network)(int64_t);
typedef int (*fn_get_tunnel_stats)(tunnel_stats_t*);

void* g_engine_handle = nullptr;
fn_start_tunnel g_start_tunnel = nullptr;
fn_stop_tunnel g_stop_tunnel = nullptr;
fn_update_network g_update_network = nullptr;
fn_get_tunnel_stats g_get_tunnel_stats = nullptr;

// Java Callback references
jobject g_protector_obj = nullptr;
jmethodID g_protect_method_id = nullptr;

jobject g_status_listener_obj = nullptr;
jmethodID g_status_method_id = nullptr;

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

// C-ABI Callback implementations bridging to Java
bool c_protect_callback(int socket_fd) {
    jobject protector_obj = nullptr;
    jmethodID protect_method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        protector_obj = g_protector_obj;
        protect_method = g_protect_method_id;
    }

    if (!protector_obj || !protect_method) {
        LOGE("c_protect_callback: SocketProtector instance is null");
        return false;
    }

    ScopedJniEnv scopedEnv;
    JNIEnv* env = scopedEnv.get();
    if (!env) {
        LOGE("c_protect_callback: Failed to acquire JNIEnv");
        return false;
    }

    jboolean result = env->CallBooleanMethod(protector_obj, protect_method, static_cast<jint>(socket_fd));
    if (env->ExceptionCheck()) {
        LOGE("c_protect_callback: Exception in Java protectSocket()");
        env->ExceptionDescribe();
        env->ExceptionClear();
        return false;
    }

    LOGD("c_protect_callback: protect(fd=%d) returned %s", socket_fd, result ? "true" : "false");
    return result == JNI_TRUE;
}

void c_status_callback(int status_code, const char* message) {
    jobject listener_obj = nullptr;
    jmethodID status_method = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);
        listener_obj = g_status_listener_obj;
        status_method = g_status_method_id;
    }

    if (!listener_obj || !status_method) {
        LOGD("c_status_callback: No status listener registered (code=%d, msg=%s)", status_code, message ? message : "");
        return;
    }

    ScopedJniEnv scopedEnv;
    JNIEnv* env = scopedEnv.get();
    if (!env) {
        LOGE("c_status_callback: Failed to acquire JNIEnv");
        return;
    }

    jstring j_msg = env->NewStringUTF(message ? message : "");
    env->CallVoidMethod(listener_obj, status_method, static_cast<jint>(status_code), j_msg);
    env->DeleteLocalRef(j_msg);

    if (env->ExceptionCheck()) {
        LOGE("c_status_callback: Exception in Java onStatusChanged()");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }
}

void cleanup_callbacks_locked(JNIEnv* env) {
    if (g_protector_obj) {
        env->DeleteGlobalRef(g_protector_obj);
        g_protector_obj = nullptr;
    }
    if (g_status_listener_obj) {
        env->DeleteGlobalRef(g_status_listener_obj);
        g_status_listener_obj = nullptr;
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
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (g_engine_handle) {
        dlclose(g_engine_handle);
        g_engine_handle = nullptr;
    }
}

/**
 * Initializes and dynamically binds the selected native protocol engine.
 *
 * @param env JNI environment.
 * @param clazz JniBridge class.
 * @param jLibName Library name, e.g., "libengine_rust.so" or "libengine_go.so".
 * @return 0 on success, negative error code on failure.
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

    LOGI("Loading native VPN engine library: %s", libName);

    // If an engine was already loaded, close it first
    if (g_engine_handle) {
        dlclose(g_engine_handle);
        g_engine_handle = nullptr;
    }

    g_start_tunnel = nullptr;
    g_stop_tunnel = nullptr;
    g_update_network = nullptr;
    g_get_tunnel_stats = nullptr;

    void* handle = dlopen(libName, RTLD_NOW | RTLD_GLOBAL);
    env->ReleaseStringUTFChars(jLibName, libName);

    if (!handle) {
        const char* err = dlerror();
        LOGE("Failed to dlopen engine: %s", err ? err : "unknown error");
        return ENGINE_ERR_GENERIC;
    }

    g_start_tunnel = reinterpret_cast<fn_start_tunnel>(dlsym(handle, "start_tunnel"));
    g_stop_tunnel = reinterpret_cast<fn_stop_tunnel>(dlsym(handle, "stop_tunnel"));
    g_update_network = reinterpret_cast<fn_update_network>(dlsym(handle, "update_network"));
    g_get_tunnel_stats = reinterpret_cast<fn_get_tunnel_stats>(dlsym(handle, "get_tunnel_stats"));

    if (!g_start_tunnel || !g_stop_tunnel || !g_update_network || !g_get_tunnel_stats) {
        LOGE("Engine library is missing required C-ABI symbols! start=%p, stop=%p, update=%p, stats=%p",
             g_start_tunnel, g_stop_tunnel, g_update_network, g_get_tunnel_stats);
        dlclose(handle);
        return ENGINE_ERR_GENERIC;
    }

    g_engine_handle = handle;
    LOGI("Native engine loaded and symbols resolved successfully");
    return ENGINE_SUCCESS;
}

/**
 * Starts the tunnel via the active native engine.
 */
JNIEXPORT jint JNICALL
Java_org_bobrdobr_vpn_core_engine_JniBridge_nativeStartTunnel(
        JNIEnv* env,
        jclass clazz,
        jint tunFd,
        jstring jConfigJson,
        jobject jProtector,
        jobject jStatusListener) {
    (void)clazz;

    fn_start_tunnel start_fn = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_engine_mutex);

        if (!g_start_tunnel) {
            LOGE("nativeStartTunnel called but no engine loaded");
            return ENGINE_ERR_NOT_RUNNING;
        }

        if (tunFd < 0 || !jConfigJson || !jProtector) {
            LOGE("nativeStartTunnel invalid arguments");
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

        start_fn = g_start_tunnel;
    }

    // Call start_tunnel OUTSIDE g_engine_mutex to eliminate any deadlock risk with callbacks
    const char* configJson = env->GetStringUTFChars(jConfigJson, nullptr);
    LOGI("Starting native tunnel with tunFd=%d...", tunFd);

    int res = start_fn(tunFd, configJson, c_protect_callback, c_status_callback);

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
        stop_fn = g_stop_tunnel;
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
        update_fn = g_update_network;
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
        stats_fn = g_get_tunnel_stats;
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
            static_cast<jlong>(stats.rx_speed_bps),
            static_cast<jlong>(stats.tx_speed_bps),
            static_cast<jlong>(stats.ping_ms),
            static_cast<jlong>(stats.last_handshake_timestamp),
            static_cast<jlong>(stats.timestamp)
    );

    return statsObj;
}

} // extern "C"
