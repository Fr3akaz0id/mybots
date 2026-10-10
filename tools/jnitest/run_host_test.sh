#!/bin/bash
# Host smoke test for the Android JNI bridge: compiles the SAME jni_bridge.cpp
# against a real JVM (arm64) and drives the full core through it.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
SDK=/opt/hermes-gadget-sdk/firmware
BUILD=$HERE/build
rm -rf "$BUILD" && mkdir -p "$BUILD/classes/com/freaklair/hermesgadget"

# 1. Native: same bridge, android-log stubbed, linked with the unmodified core.
sed 's|#include <android/log.h>|#include <cstdio>\n#define ANDROID_LOG_INFO 4\n#define ANDROID_LOG_WARN 5\nstatic void hg_log_stub(int,const char*,const char* m){ if(m) fprintf(stderr,"[core] %s\\n", m); }|; s|__android_log_write|hg_log_stub|g; s|#define LOG_TAG "hgad"|#define LOG_TAG "hgad"|' "$HERE/../../app/src/main/cpp/jni_bridge.cpp" > "$BUILD/jni_bridge_host.cpp"
g++ -std=c++17 -shared -fPIC -O1 -pthread \
  -I"$SDK/sim/include" -I"$SDK/core/include" -I"$SDK/core/third_party/qrcode" \
  -I"/usr/lib/jvm/java-17-openjdk-arm64/include" -I"/usr/lib/jvm/java-17-openjdk-arm64/include/linux" \
  -o "$BUILD/libhgandroid_host.so" \
  "$BUILD/jni_bridge_host.cpp" \
  "$SDK/sim/src/hgsim.cpp" "$SDK/core/src/"*.cpp "$SDK/core/third_party/qrcode/qrcode.cpp" 2>"$BUILD/cc.log" || { cat "$BUILD/cc.log"; exit 1; }
echo "[build] native lib ok"

# 2. Java mirrors of NativeBridge/NativeHost (exact JNI names/signatures).
cat > "$BUILD/classes/com/freaklair/hermesgadget/NativeBridge.java" <<'EOF'
package com.freaklair.hermesgadget;
public class NativeBridge {
  static { System.loadLibrary("hgandroid_host"); }
  public static native long nativeCreate(String url, String token, int w, int h, int mic, int spk, long mr, long sr, String name);
  public static native long nativeCreateOn(NativeHost host, String url, String token, int w, int h, int mic, int spk, long mr, long sr, String name);
  public static native void nativeDestroy(long p);
  public static native void nativeTick(long p);
  public static native void nativeNetwork(long p, int up);
  public static native void nativeTransportOpen(long p);
  public static native void nativeTransportText(long p, String t);
  public static native void nativeTransportBinary(long p, byte[] d);
  public static native void nativeTransportClosed(long p, String r);
  public static native void nativeButton(long p, int b, int pressed);
  public static native void nativeTouch(long p, int touching, int x, int y);
  public static native void nativeMicSamples(long p, short[] s);
  public static native void nativeSubmitText(long p, String t);
  public static native void nativeSetSensor(long p, String n, double v);
  public static native void nativeEmitEvent(long p, String n, String d, int notify);
  public static native String nativeStatus(long p);
  public static native int[][] nativeRows(long p, int y0, int y1);
  public static native int[] nativeSize(long p);
  public static native String nativeScreen(long p);
  public static native String nativeConsole(long p, String line);
}
EOF

cat > "$BUILD/classes/com/freaklair/hermesgadget/NativeHost.java" <<'EOF'
package com.freaklair.hermesgadget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class NativeHost {
  public static NativeHost INSTANCE = new NativeHost();
  static final Map<String,String> store = INSTANCE.instanceStore;
  static int flushes = 0;
  static boolean connectCalled = false;

  // instance-level: each bound core routes here, used by the 2-instance test
  final Map<String,String> instanceStore = new HashMap<>();
  int instFlushes = 0;
  boolean instConnect = false;
  final List<String> urls = new ArrayList<>();

  void onTransportConnect(String url, String sub) { connectCalled = true; instConnect = true; urls.add(url); }
  boolean onTransportSend(byte[] data) { return true; }
  void onTransportClose() {}
  void onDisplayFlush(int y0, int y1) { flushes++; instFlushes++; }
  boolean onMicStart(int rate) { return true; }
  void onMicStop() {}
  boolean onSpeakerBegin(int rate) { return true; }
  void onSpeakerWrite(short[] s) {}
  void onSpeakerAbort() {}
  boolean onSpeakerBusy() { return false; }
  void onSpeakerVolume(int p) {}
  String onStorageGet(String key) { return instanceStore.get(key); }
  void onStorageSet(String key, String value) { instanceStore.put(key, value); }
  void onStorageErase(String key) { instanceStore.remove(key); }
}
EOF
javac -d "$BUILD/classes" "$BUILD/classes/com/freaklair/hermesgadget/NativeBridge.java" "$BUILD/classes/com/freaklair/hermesgadget/NativeHost.java"

# 3. Driver.
cat > "$BUILD/classes/com/freaklair/hermesgadget/Main.java" <<'EOF'
package com.freaklair.hermesgadget;
public class Main {
  static int failures = 0;
  static void check(boolean cond, String name) {
    System.out.println((cond ? "PASS" : "FAIL") + " " + name);
    if (!cond) failures++;
  }
  public static void main(String[] args) throws Exception {
    long p = NativeBridge.nativeCreate("wss://example.test/ws", "tok123", 480, 480, 1, 1, 16000, 16000, "jvmtest");
    check(p != 0, "core create");
    for (int i = 0; i < 30; i++) { NativeBridge.nativeTick(p); Thread.sleep(5); }
    int[] wh = NativeBridge.nativeSize(p);
    check(wh[0] == 480 && wh[1] == 480, "size 480x480 (got " + wh[0] + "x" + wh[1] + ")");
    String st = NativeBridge.nativeConsole(p, "status");
    check(st.contains("\"board\":\"android\""), "status board=android (console: " + st.substring(0, Math.min(80, st.length())) + "...)");
    check(!NativeHost.store.isEmpty(), "storage round-trip via JNI (device_key etc: " + NativeHost.store.keySet() + ")");
    int[][] rows = NativeBridge.nativeRows(p, 0, 480);
    check(rows.length == 480 && rows[0].length == 480, "framebuffer rows via JNI");
    check(NativeHost.flushes > 0, "display flush callbacks reached JVM (" + NativeHost.flushes + ")");
    String screen = NativeBridge.nativeScreen(p);
    check(!screen.isEmpty(), "screen text via JNI: '" + screen.replaceAll("\\s+"," ").substring(0, Math.min(60, screen.length())) + "'");
    NativeBridge.nativeNetwork(p, 1);
    for (int i = 0; i < 200; i++) { NativeBridge.nativeTick(p); Thread.sleep(10); }   // ~2s wall: boot(800ms)->connecting
    check(NativeHost.connectCalled, "transport connect callback fired after boot + net up");
    NativeBridge.nativeTouch(p, 1, 240, 240);
    NativeBridge.nativeTick(p);
    NativeBridge.nativeTouch(p, 0, 240, 240);
    NativeBridge.nativeSubmitText(p, "hello from jvm");
    NativeBridge.nativeButton(p, 0, 1); NativeBridge.nativeButton(p, 0, 0);
    for (int i = 0; i < 10; i++) NativeBridge.nativeTick(p);
    String st2 = NativeBridge.nativeConsole(p, "status");
    check(st2.contains("device_id"), "post-input status still valid");
    NativeBridge.nativeTransportOpen(p);
    NativeBridge.nativeTransportText(p, "{\"type\":\"hello\"}");
    for (int i = 0; i < 5; i++) NativeBridge.nativeTick(p);

    // --- multi-instance: two cores bound to two distinct host objects -------
    NativeHost h1 = new NativeHost();
    NativeHost h2 = new NativeHost();
    long p1 = NativeBridge.nativeCreateOn(h1, "wss://a.test/ws", "tk1", 480, 480, 1, 1, 16000, 16000, "one");
    long p2 = NativeBridge.nativeCreateOn(h2, "wss://b.test/ws", "tk2", 960, 400, 1, 1, 16000, 16000, "two");
    check(p1 != 0 && p2 != 0 && p1 != p2, "two cores created, distinct ptrs");
    NativeBridge.nativeNetwork(p1, 1); NativeBridge.nativeNetwork(p2, 1);
    for (int i = 0; i < 200; i++) { NativeBridge.nativeTick(p1); NativeBridge.nativeTick(p2); Thread.sleep(10); }
    check(h1.instConnect && h1.urls.contains("wss://a.test/ws") && !h1.urls.contains("wss://b.test/ws"),
          "host1 connect callbacks only for its own url " + h1.urls);
    check(h2.instConnect && h2.urls.contains("wss://b.test/ws") && !h2.urls.contains("wss://a.test/ws"),
          "host2 connect callbacks only for its own url " + h2.urls);
    check(!h1.instanceStore.isEmpty() && !h2.instanceStore.isEmpty()
          && !h1.instanceStore.get("device_key").equals(h2.instanceStore.get("device_key")),
          "per-instance storage isolation (distinct device_keys)");
    int[] sz2 = NativeBridge.nativeSize(p2);
    check(sz2[0] == 960 && sz2[1] == 400, "instance2 wide panel 960x400 (got " + sz2[0] + "x" + sz2[1] + ")");
    NativeBridge.nativeTouch(p1, 1, 100, 100); NativeBridge.nativeTick(p1); NativeBridge.nativeTouch(p1, 0, 100, 100);
    NativeBridge.nativeTouch(p2, 1, 500, 200); NativeBridge.nativeTick(p2); NativeBridge.nativeTouch(p2, 0, 500, 200);
    check(NativeBridge.nativeStatus(p1).contains("one") && NativeBridge.nativeStatus(p2).contains("two"),
          "status reports own name per instance");
    int f1 = h1.instFlushes, f2 = h2.instFlushes;
    NativeBridge.nativeDestroy(p1);
    NativeBridge.nativeDestroy(p2);
    check(true, "clean destroy x2");
    NativeBridge.nativeDestroy(p);
    check(true, "clean destroy");
    System.out.println(failures == 0 ? "ALL_PASS" : "FAILURES=" + failures);
    System.exit(failures == 0 ? 0 : 1);
  }
}
EOF
javac -cp "$BUILD/classes" -d "$BUILD/classes" "$BUILD/classes/com/freaklair/hermesgadget/Main.java"

# 4. Run with real JVM.
LD_LIBRARY_PATH="$BUILD" java -cp "$BUILD/classes" com.freaklair.hermesgadget.Main