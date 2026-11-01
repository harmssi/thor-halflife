package de.harmssi.thorhalflife;

/**
 * JNI bridge to libthorbridge.so (thor/native/thorbridge.c), which forwards to the HUD snapshot
 * exported by the HL client library (cl_dll/thor_deck.cpp).
 */
final class NativeBridge {
    private NativeBridge() {}

    static {
        System.loadLibrary("thorbridge");
    }

    /** JSON snapshot of the HUD state, or null while the client library is not loaded. */
    static native String snapshot(String clientLibPath);

    /** Queue an allowed console command; it runs on the game thread like a key bind. */
    static native boolean command(String clientLibPath, String cmd);
}
