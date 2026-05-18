package ink.mol.raw_cast

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import ink.mol.raw_cast.wrapper.DisplayControl
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Acquires Bitmaps directly from SurfaceFlinger via reflection, mirroring the
 * approach used by DroidCast_raw / scrcpy. Supports SDK 23..35.
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "UnsafeDynamicallyLoadedCode")
object ScreenCaptor {
    private val sdkInt: Int = Build.VERSION.SDK_INT
    private var surfaceControlClass: Class<*>? = null
    private var getBuiltInDisplayMethod: Method? = null

    /** Whether the JNI library that does HardwareBuffer zero-copy was loaded. */
    @Volatile
    var nativeAvailable: Boolean =
        sdkInt >= Build.VERSION_CODES.S && NativeLibHelper.loadLibs()
        private set

    init {
        try {
            surfaceControlClass = if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Class.forName("android.window.ScreenCapture")
            } else {
                Class.forName("android.view.SurfaceControl")
            }
        } catch (e: ClassNotFoundException) {
            throw RuntimeException(e)
        }
    }

    private fun getBuiltInDisplayMethod(): Method {
        if (getBuiltInDisplayMethod == null) {
            getBuiltInDisplayMethod = if (sdkInt < Build.VERSION_CODES.Q) {
                surfaceControlClass!!.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
            } else {
                surfaceControlClass!!.getMethod("getInternalDisplayToken")
            }
        }
        return getBuiltInDisplayMethod!!
    }

    private fun getBuiltInDisplay(): IBinder? {
        try {
            if (sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val ids = DisplayControl.getPhysicalDisplayIds()
                if (ids != null && ids.isNotEmpty()) {
                    return DisplayControl.getPhysicalDisplayToken(ids[0])
                }
                return DisplayControl.getPhysicalDisplayToken(0)
            }
            val m = getBuiltInDisplayMethod()
            return if (sdkInt < Build.VERSION_CODES.Q) m.invoke(null, 0) as IBinder
            else m.invoke(null) as IBinder
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private data class CachedArgs(
        val width: Int, val height: Int, val pixfmt: Int?, val args: Any
    )

    @Volatile
    private var cachedArgs: CachedArgs? = null
    @Volatile
    private var setPixelFormatMethod: Method? = null
    @Volatile
    private var setPixelFormatUnsupported: Boolean = false
    @Volatile
    private var setPixelFormatWarningPrinted: Boolean = false

    /**
     * Capture a fresh bitmap. `surfacePixelFormat` is the
     * android.graphics.PixelFormat constant requested from the screenshot
     * pipeline (e.g. PixelFormat.RGB_565 or PixelFormat.RGBA_8888). May be
     * null to let the system pick.
     */
    @SuppressLint("NewApi", "BlockedPrivateApi")
    fun screenshot(width: Int, height: Int, surfacePixelFormat: Int? = null): Bitmap? {
        return try {
            when {
                sdkInt >= Build.VERSION_CODES.S -> screenshotS(width, height, surfacePixelFormat)
                sdkInt >= Build.VERSION_CODES.P -> {
                    val m = surfaceControlClass!!.getDeclaredMethod(
                        "screenshot", Rect::class.java,
                        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(null, Rect(), width, height, 0) as Bitmap?
                }
                else -> {
                    val m = surfaceControlClass!!.getDeclaredMethod(
                        "screenshot", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                    )
                    m.invoke(null, width, height) as Bitmap?
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @SuppressLint("NewApi", "BlockedPrivateApi")
    private fun screenshotS(width: Int, height: Int, pixfmt: Int?): Bitmap? {
        val isU = sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        val argsClass: Class<*>
        val builderClass: Class<*>
        if (isU) {
            argsClass = Class.forName("android.window.ScreenCapture\$DisplayCaptureArgs")
            builderClass = Class.forName("android.window.ScreenCapture\$DisplayCaptureArgs\$Builder")
        } else {
            argsClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs")
            builderClass = Class.forName("android.view.SurfaceControl\$DisplayCaptureArgs\$Builder")
        }

        val pixelFormatMethod = pixfmt?.let { resolveSetPixelFormatMethod(builderClass) }
        val cachePixfmt = if (pixelFormatMethod != null) pixfmt else null
        val cached = cachedArgs
        val args: Any = if (cached != null && cached.width == width && cached.height == height && cached.pixfmt == cachePixfmt) {
            cached.args
        } else {
            val ctor: Constructor<*> = builderClass.getDeclaredConstructor(IBinder::class.java)
            val builder = ctor.newInstance(getBuiltInDisplay())
            builderClass.getDeclaredMethod("setSize", Int::class.java, Int::class.java)
                .invoke(builder, width, height)
            val appliedPixfmt = if (pixfmt != null && pixelFormatMethod != null) {
                if (applySetPixelFormat(pixelFormatMethod, builder, pixfmt)) pixfmt else null
            } else {
                null
            }
            val a = builderClass.getDeclaredMethod("build").invoke(builder)!!
            cachedArgs = CachedArgs(width, height, appliedPixfmt, a)
            a
        }

        val captureDisplay = surfaceControlClass!!.getDeclaredMethod("captureDisplay", argsClass)
        val sshb = captureDisplay.invoke(null, args) ?: return null
        val sshbClass = sshb.javaClass
        val colorSpace =
            sshbClass.getDeclaredMethod("getColorSpace").invoke(sshb) as ColorSpace
        val hb = sshbClass.getDeclaredMethod("getHardwareBuffer").invoke(sshb) as HardwareBuffer
        return hb.use { Bitmap.wrapHardwareBuffer(it, colorSpace) }
    }

    private fun resolveSetPixelFormatMethod(builderClass: Class<*>): Method? {
        if (setPixelFormatUnsupported) return null
        setPixelFormatMethod?.let { return it }
        return try {
            builderClass.getMethod("setPixelFormat", Int::class.java).also {
                it.isAccessible = true
                setPixelFormatMethod = it
            }
        } catch (e: NoSuchMethodException) {
            setPixelFormatUnsupported = true
            warnSetPixelFormatFallback("DisplayCaptureArgs.Builder.setPixelFormat(int) is unavailable")
            null
        }
    }

    private fun applySetPixelFormat(method: Method, builder: Any, pixfmt: Int): Boolean {
        return try {
            method.invoke(builder, pixfmt)
            true
        } catch (e: Exception) {
            setPixelFormatUnsupported = true
            warnSetPixelFormatFallback("DisplayCaptureArgs.Builder.setPixelFormat(int) failed")
            false
        }
    }

    private fun warnSetPixelFormatFallback(reason: String) {
        if (!setPixelFormatWarningPrinted) {
            setPixelFormatWarningPrinted = true
            System.err.println("[raw_cast] $reason; falling back to default capture pixel format")
        }
    }
}
