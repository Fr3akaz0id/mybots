package com.freaklair.hermesgadget

// JNI surface around libhgandroid.so (the gadget core, hgsim C ABI).
// `object` so every external fun compiles to a JNI instance method bound to
// INSTANCE, matching the jni_bridge.cpp signatures (jobject receiver).
object NativeBridge {
    external fun nativeCreate(serverUrl: String, token: String, width: Int, height: Int,
                              hasMic: Int, hasSpeaker: Int, micRate: Long, speakerRate: Long,
                              name: String): Long
    // Create against an explicit NativeHost instance: every callback of that
    // core routes there, no shared static. This is the multi-agent path.
    external fun nativeCreateOn(host: NativeHost?, serverUrl: String, token: String,
                                width: Int, height: Int,
                                hasMic: Int, hasSpeaker: Int, micRate: Long, speakerRate: Long,
                                name: String): Long
    external fun nativeDestroy(ptr: Long)
    external fun nativeTick(ptr: Long)
    external fun nativeNetwork(ptr: Long, up: Int)
    external fun nativeTransportOpen(ptr: Long)
    external fun nativeTransportText(ptr: Long, text: String)
    external fun nativeTransportBinary(ptr: Long, data: ByteArray)
    external fun nativeTransportClosed(ptr: Long, reason: String)
    external fun nativeButton(ptr: Long, button: Int, pressed: Int)
    external fun nativeTouch(ptr: Long, touching: Int, x: Int, y: Int)
    external fun nativeMicSamples(ptr: Long, samples: ShortArray)
    external fun nativeSubmitText(ptr: Long, text: String)
    external fun nativeSetSensor(ptr: Long, name: String, value: Double)
    external fun nativeEmitEvent(ptr: Long, name: String, dataJson: String, notify: Int)
    external fun nativeStatus(ptr: Long): String
    external fun nativeRows(ptr: Long, y0: Int, y1: Int): Array<IntArray>
    external fun nativeSize(ptr: Long): IntArray
    external fun nativeScreen(ptr: Long): String
    external fun nativeConsole(ptr: Long, line: String): String

    init { System.loadLibrary("hgandroid") }
}
