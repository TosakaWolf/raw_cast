package com.shiyori.raw_cast

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Point
import android.os.IBinder
import android.view.IWindowManager
import java.lang.reflect.Method

@SuppressLint("PrivateApi", "DiscouragedPrivateApi")
class DisplayUtil {

    private var iWindowManager: IWindowManager? = null

    init {
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService: Method =
                serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            val ws: Any? = getService.invoke(null, Context.WINDOW_SERVICE)
            iWindowManager = IWindowManager.Stub.asInterface(ws as IBinder?)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCurrentDisplaySize(): Point {
        return try {
            val p = Point()
            iWindowManager?.getBaseDisplaySize(0, p)
            if (p.x <= 0 || p.y <= 0) {
                iWindowManager?.getInitialDisplaySize(0, p)
            }
            p
        } catch (e: Exception) {
            e.printStackTrace()
            Point()
        }
    }

    fun getScreenRotation(): Int {
        var rotation = 0
        try {
            val cls = iWindowManager!!.javaClass
            rotation = try {
                cls.getMethod("getRotation").invoke(iWindowManager) as Int
            } catch (e: NoSuchMethodException) {
                cls.getMethod("getDefaultDisplayRotation").invoke(iWindowManager) as Int
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return rotation
    }
}
