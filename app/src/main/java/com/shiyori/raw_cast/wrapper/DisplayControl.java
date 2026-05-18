package com.shiyori.raw_cast.wrapper;

import android.annotation.SuppressLint;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Reflection bridge to com.android.server.display.DisplayControl, required on
 * Android 14+ to obtain a physical display token for screenshotting.
 *
 * Adapted from scrcpy:
 * <a href="https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/wrappers/DisplayControl.java">DisplayControl.java</a>
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class DisplayControl {

    private static final Class<?> CLASS;

    static {
        Class<?> displayControlClass = null;
        try {
            Class<?> classLoaderFactoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory");
            Method createClassLoaderMethod = classLoaderFactoryClass.getDeclaredMethod(
                    "createClassLoader", String.class, String.class, String.class,
                    ClassLoader.class, int.class, boolean.class, String.class);
            ClassLoader classLoader = (ClassLoader) createClassLoaderMethod.invoke(
                    null, "/system/framework/services.jar", null, null,
                    ClassLoader.getSystemClassLoader(), 0, true, null);

            displayControlClass = classLoader.loadClass("com.android.server.display.DisplayControl");

            Method loadMethod = Runtime.class.getDeclaredMethod("loadLibrary0", Class.class, String.class);
            loadMethod.setAccessible(true);
            loadMethod.invoke(Runtime.getRuntime(), displayControlClass, "android_servers");
        } catch (Throwable e) {
            System.err.printf("Could not initialize DisplayControl : %s%n", e);
        }
        CLASS = displayControlClass;
    }

    private static Method getPhysicalDisplayTokenMethod;
    private static Method getPhysicalDisplayIdsMethod;

    private DisplayControl() {
    }

    public static IBinder getPhysicalDisplayToken(long physicalDisplayId) {
        try {
            if (getPhysicalDisplayTokenMethod == null) {
                getPhysicalDisplayTokenMethod = CLASS.getMethod("getPhysicalDisplayToken", long.class);
            }
            return (IBinder) getPhysicalDisplayTokenMethod.invoke(null, physicalDisplayId);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            System.err.printf("Could not invoke getPhysicalDisplayToken : %s%n", e);
            return null;
        }
    }

    public static long[] getPhysicalDisplayIds() {
        try {
            if (getPhysicalDisplayIdsMethod == null) {
                getPhysicalDisplayIdsMethod = CLASS.getMethod("getPhysicalDisplayIds");
            }
            return (long[]) getPhysicalDisplayIdsMethod.invoke(null);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            System.err.printf("Could not invoke getPhysicalDisplayIds : %s%n", e);
            return null;
        }
    }
}
