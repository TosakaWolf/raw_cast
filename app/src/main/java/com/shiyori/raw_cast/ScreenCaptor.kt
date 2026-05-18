package com.shiyori.raw_cast

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.IBinder
import com.shiyori.raw_cast.wrapper.DisplayControl
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Acquires Bitmaps directly from SurfaceFlinger via reflection, mirroring the
 * approach used by DroidCast_raw / scrcpy. Supports SDK 23..35.
 */
@SuppressLint("PrivateApi", "BlockedPrivateApi", "UnsafeDynamicallyLoadedCode")
object ScreenCaptor {
    private const val ANDROID_15 = 35
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

    private data class ScreenCaptureApi(
        val argsClass: Class<*>,
        val builderClass: Class<*>,
        val builderCtor: Constructor<*>,
        val setSizeMethod: Method,
        val buildMethod: Method,
        val captureDisplayMethod: Method
    )

    private data class BufferAccessors(
        val owner: Class<*>,
        val getColorSpaceMethod: Method,
        val getHardwareBufferMethod: Method
    )

    @Volatile
    private var cachedArgs: CachedArgs? = null
    @Volatile
    private var screenCaptureApi: ScreenCaptureApi? = null
    @Volatile
    private var bufferAccessors: BufferAccessors? = null
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
        val api = getScreenCaptureApi()
        val pixelFormatMethod = pixfmt?.let { resolveSetPixelFormatMethod(api.builderClass) }
        val cachePixfmt = if (pixelFormatMethod != null) pixfmt else null
        val cached = cachedArgs
        val args: Any = if (cached != null && cached.width == width && cached.height == height && cached.pixfmt == cachePixfmt) {
            cached.args
        } else {
            val builder = api.builderCtor.newInstance(getBuiltInDisplay())
            api.setSizeMethod.invoke(builder, width, height)
            val appliedPixfmt = if (pixfmt != null && pixelFormatMethod != null) {
                if (isAndroid15OrAbove()) {
                    if (applySetPixelFormatCompat(pixelFormatMethod, builder, pixfmt)) pixfmt else null
                } else {
                    pixelFormatMethod.invoke(builder, pixfmt)
                    pixfmt
                }
            } else {
                null
            }
            val a = api.buildMethod.invoke(builder)!!
            cachedArgs = CachedArgs(width, height, appliedPixfmt, a)
            a
        }

        val sshb = api.captureDisplayMethod.invoke(null, args) ?: return null
        val accessors = getBufferAccessors(sshb.javaClass)
        val colorSpace = accessors.getColorSpaceMethod.invoke(sshb) as ColorSpace
        val hb = accessors.getHardwareBufferMethod.invoke(sshb) as HardwareBuffer
        return hb.use { Bitmap.wrapHardwareBuffer(it, colorSpace) }
    }

    private fun getScreenCaptureApi(): ScreenCaptureApi {
        screenCaptureApi?.let { return it }
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

        val api = ScreenCaptureApi(
            argsClass = argsClass,
            builderClass = builderClass,
            builderCtor = builderClass.getDeclaredConstructor(IBinder::class.java).also {
                it.isAccessible = true
            },
            setSizeMethod = findScreenCaptureMethod(
                builderClass,
                "setSize",
                Int::class.java,
                Int::class.java
            ).also { it.isAccessible = true },
            buildMethod = findScreenCaptureMethod(builderClass, "build").also { it.isAccessible = true },
            captureDisplayMethod = findScreenCaptureMethod(surfaceControlClass!!, "captureDisplay", argsClass).also {
                it.isAccessible = true
            }
        )
        screenCaptureApi = api
        return api
    }

    private fun getBufferAccessors(owner: Class<*>): BufferAccessors {
        bufferAccessors?.let {
            if (it.owner == owner) return it
        }
        val accessors = BufferAccessors(
            owner = owner,
            getColorSpaceMethod = findScreenCaptureMethod(owner, "getColorSpace").also { it.isAccessible = true },
            getHardwareBufferMethod = findScreenCaptureMethod(owner, "getHardwareBuffer").also {
                it.isAccessible = true
            }
        )
        bufferAccessors = accessors
        return accessors
    }

    private fun resolveSetPixelFormatMethod(builderClass: Class<*>): Method? {
        if (setPixelFormatUnsupported) return null
        setPixelFormatMethod?.let { return it }
        if (!isAndroid15OrAbove()) {
            return builderClass.getDeclaredMethod("setPixelFormat", Int::class.java).also {
                it.isAccessible = true
                setPixelFormatMethod = it
            }
        }
        return try {
            findDeclaredFirstMethod(builderClass, "setPixelFormat", Int::class.java).also {
                it.isAccessible = true
                setPixelFormatMethod = it
            }
        } catch (e: NoSuchMethodException) {
            setPixelFormatUnsupported = true
            warnSetPixelFormatFallback("DisplayCaptureArgs.Builder.setPixelFormat(int) is unavailable")
            null
        }
    }

    private fun applySetPixelFormatCompat(method: Method, builder: Any, pixfmt: Int): Boolean {
        return try {
            method.invoke(builder, pixfmt)
            true
        } catch (e: Exception) {
            setPixelFormatUnsupported = true
            warnSetPixelFormatFallback("DisplayCaptureArgs.Builder.setPixelFormat(int) failed")
            false
        }
    }

    private fun findScreenCaptureMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        return if (isAndroid15OrAbove()) {
            findDeclaredFirstMethod(owner, name, *parameterTypes)
        } else {
            owner.getDeclaredMethod(name, *parameterTypes)
        }
    }

    private fun findDeclaredFirstMethod(owner: Class<*>, name: String, vararg parameterTypes: Class<*>): Method {
        try {
            return owner.getDeclaredMethod(name, *parameterTypes)
        } catch (e: NoSuchMethodException) {
            var cls = owner.superclass
            while (cls != null) {
                try {
                    return cls.getDeclaredMethod(name, *parameterTypes)
                } catch (ignored: NoSuchMethodException) {
                    cls = cls.superclass
                }
            }
            return owner.getMethod(name, *parameterTypes)
        }
    }

    private fun warnSetPixelFormatFallback(reason: String) {
        if (!setPixelFormatWarningPrinted) {
            setPixelFormatWarningPrinted = true
            System.err.println("[raw_cast] $reason; falling back to default capture pixel format")
        }
    }

    private fun isAndroid15OrAbove(): Boolean = sdkInt >= ANDROID_15
}
