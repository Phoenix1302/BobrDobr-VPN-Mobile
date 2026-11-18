package org.bobrdobr.vpn.engine.rust

/**
 * Interface and loader for libengine_rust.so (AmneziaWG & TUN zero-alloc loop)
 */
object RustEngine {
    private var isLoaded = false

    @Synchronized
    fun loadNativeLibrary(): Boolean {
        if (!isLoaded) {
            try {
                System.loadLibrary("engine_rust")
                isLoaded = true
            } catch (t: Throwable) {
                isLoaded = false
            }
        }
        return isLoaded
    }
}
