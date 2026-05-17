package android.view;

import android.graphics.Point;
import android.view.IRotationWatcher;

/**
 * Stub of the framework-hidden IWindowManager. The real implementation is
 * loaded at runtime via the Java class delegation model when running under
 * app_process.
 */
interface IWindowManager {

    void getInitialDisplaySize(int displayId, out Point size);

    void getBaseDisplaySize(int displayId, out Point size);

    void getRealDisplaySize(out Point paramPoint);

    void removeRotationWatcher(IRotationWatcher watcher);
}
