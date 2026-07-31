package hev.htproxy

import androidx.annotation.Keep

@Keep // Prevents Android from renaming this during release builds
object TProxyService {
    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic
    external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    external fun TProxyStopService(): Boolean

    // THE MISSING PIECE: Added to satisfy the native C-engine's JNI registration
    @JvmStatic
    external fun TProxyIsRunning(): Boolean

    @JvmStatic
    external fun TProxyGetStats(): LongArray
}