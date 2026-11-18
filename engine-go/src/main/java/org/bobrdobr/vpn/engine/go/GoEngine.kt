package org.bobrdobr.vpn.engine.go

/**
 * Interface and loader for libengine_go.so (AnyConnect CSTP/DTLS + uTLS worker)
 */
object GoEngine {
    private var isLoaded = false

    @Synchronized
    fun loadNativeLibrary(): Boolean {
        if (!isLoaded) {
            try {
                System.loadLibrary("engine_go")
                isLoaded = true
            } catch (t: Throwable) {
                isLoaded = false
            }
        }
        return isLoaded
    }
}
