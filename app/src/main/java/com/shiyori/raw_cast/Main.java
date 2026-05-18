package com.shiyori.raw_cast;

/**
 * app_process entry point. The actual logic lives in {@link KtMain}; this
 * shim exists so the binary class name is short and stable for use in
 * `adb shell ... app_process / com.shiyori.raw_cast.Main ...` commands, mirroring
 * how DroidCast_raw is launched.
 */
public class Main {
    public static void main(String[] args) {
        new KtMain().main(args);
    }

    private Main() {
    }
}
