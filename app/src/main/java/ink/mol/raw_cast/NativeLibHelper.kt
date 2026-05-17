package ink.mol.raw_cast

import android.os.Build

/**
 * Loads the bundled JNI library directly from the APK that was pushed to
 * /data/local/tmp (CLASSPATH env var) since raw_cast is launched non-installed
 * via app_process and therefore has no nativeLibraryDir.
 */
object NativeLibHelper {
    @Volatile
    private var loaded: Boolean = false
    private val lock = Any()

    fun loadLibs(): Boolean {
        if (loaded) return true
        synchronized(lock) {
            if (loaded) return true
            try {
                val classpath = System.getenv("CLASSPATH")
                val abi = Build.SUPPORTED_ABIS?.firstOrNull()
                if (!classpath.isNullOrEmpty() && !abi.isNullOrEmpty()) {
                    System.load("$classpath!/lib/$abi/libraw_cast.so")
                } else {
                    System.loadLibrary("raw_cast")
                }
                loaded = true
            } catch (e: UnsatisfiedLinkError) {
                e.printStackTrace()
            }
            return loaded
        }
    }
}
