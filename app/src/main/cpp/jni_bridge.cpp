// JNI bridge: Android HAL for the Hermes gadget core.
// Mirrors firmware/sim/src/hgsim.cpp for the desktop simulator: every HAL
// callback forwards to NativeHost, every core entry point is exposed here.
// All hgsim_* calls and host callbacks run on the single core thread.
#define _GNU_SOURCE 1
#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/random.h>
#include <time.h>
#include <unistd.h>

#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "hgsim.h"
#include "hg/hal.hpp"

#define LOG_TAG "hgad"

namespace {

JavaVM* g_vm = nullptr;
jclass g_host_cls = nullptr;
jobject g_host_obj = nullptr;  // global: NativeHost.INSTANCE (legacy / null-user fallback)

// ---- per-instance host bindings -------------------------------------------
// Each core instance owns a HostCtx holding a global ref to its own Kotlin
// NativeHost. SimHal copies hgsim_host by value per hgsim*, so h.user reaches
// every callback instance-local. The hgsim*->HostCtx map exists only so
// nativeDestroy can release the ref; callbacks read u directly, no map access.
struct HostCtx { jobject obj; };
std::mutex g_map_mu;
std::unordered_map<hgsim*, HostCtx*> g_ctx;

inline jobject host_of(void* u) {
  HostCtx* c = static_cast<HostCtx*>(u);
  return c ? c->obj : g_host_obj;
}

jmethodID m_connect = nullptr;    // (String,String)V
jmethodID m_send = nullptr;       // (byte[])Z
jmethodID m_sendText = nullptr;   // (String)Z — optional: true text frames
jmethodID m_close = nullptr;      // ()V
jmethodID m_flush = nullptr;      // (int,int)V
jmethodID m_micStart = nullptr;   // (int)Z
jmethodID m_micStop = nullptr;    // ()V
jmethodID m_spkBegin = nullptr;   // (int)Z
jmethodID m_spkWrite = nullptr;   // (short[])V — null = end-of-stream
jmethodID m_spkAbort = nullptr;   // ()V
jmethodID m_spkBusy = nullptr;    // ()Z
jmethodID m_spkVolume = nullptr;  // (int)V
jmethodID m_storeGet = nullptr;   // (String)String
jmethodID m_storeSet = nullptr;   // (String,String)V
jmethodID m_storeErase = nullptr; // (String)V

JNIEnv* env() {
  JNIEnv* e = nullptr;
  if (g_vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) {
#if defined(__ANDROID__)
    g_vm->AttachCurrentThread(&e, nullptr);
#else
    // OpenJDK's C++ jni.h takes void**.
    g_vm->AttachCurrentThread(reinterpret_cast<void**>(&e), nullptr);
#endif
  }
  return e;
}

std::string toStr(JNIEnv* e, jstring s) {
  if (!s) return {};
  const char* c = e->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) e->ReleaseStringUTFChars(s, c);
  return r;
}

// ---- host callbacks --------------------------------------------------------

void cb_connect(void* u, const char* url, const char* sub) {
  JNIEnv* e = env();
  jstring ju = e->NewStringUTF(url ? url : "");
  jstring js = e->NewStringUTF(sub ? sub : "");
  e->CallVoidMethod(host_of(u), m_connect, ju, js);
  e->DeleteLocalRef(ju);
  e->DeleteLocalRef(js);
}

int cb_send(void* u, const char* data, size_t len) {
  JNIEnv* e = env();
  jbyteArray arr = e->NewByteArray((jsize)len);
  if (!arr) return 0;
  e->SetByteArrayRegion(arr, 0, (jsize)len, reinterpret_cast<const jbyte*>(data));
  jboolean ok = e->CallBooleanMethod(host_of(u), m_send, arr);
  e->DeleteLocalRef(arr);
  return ok ? 1 : 0;
}
// Core JSON frames must leave as WebSocket TEXT frames (the hub rejects binary
// during the handshake). Falls back to the byte path when the host lacks it.
int cb_send_text(void* u, const char* data, size_t len) {
  if (!m_sendText) return cb_send(u, data, len);
  JNIEnv* e = env();
  jstring js = e->NewStringUTF(data);
  if (!js) return 0;
  jboolean ok = e->CallBooleanMethod(host_of(u), m_sendText, js);
  e->DeleteLocalRef(js);
  return ok ? 1 : 0;
}

void cb_close(void* u) { env()->CallVoidMethod(host_of(u), m_close); }
void cb_flush(void* u, int y0, int y1) { env()->CallVoidMethod(host_of(u), m_flush, y0, y1); }

int cb_mic_start(void* u, uint32_t rate) {
  return env()->CallBooleanMethod(host_of(u), m_micStart, (jint)rate) ? 1 : 0;
}
void cb_mic_stop(void* u) { env()->CallVoidMethod(host_of(u), m_micStop); }

int cb_spk_begin(void* u, uint32_t rate) {
  return env()->CallBooleanMethod(host_of(u), m_spkBegin, (jint)rate) ? 1 : 0;
}
void cb_spk_write(void* u, const int16_t* samples, size_t count) {
  JNIEnv* e = env();
  if (!samples || !count) return;
  jshortArray arr = e->NewShortArray((jsize)count);
  if (!arr) return;
  e->SetShortArrayRegion(arr, 0, (jsize)count, samples);
  e->CallVoidMethod(host_of(u), m_spkWrite, arr);
  e->DeleteLocalRef(arr);
}
void cb_spk_end(void* u) {
  env()->CallVoidMethod(host_of(u), m_spkWrite, nullptr);  // null = end-of-stream
}
void cb_spk_abort(void* u) { env()->CallVoidMethod(host_of(u), m_spkAbort); }
int cb_spk_busy(void* u) { return env()->CallBooleanMethod(host_of(u), m_spkBusy) ? 1 : 0; }
void cb_spk_volume(void* u, int percent) { env()->CallVoidMethod(host_of(u), m_spkVolume, (jint)percent); }

int cb_store_get(void* u, const char* key, char* out, size_t cap) {
  JNIEnv* e = env();
  jstring jk = e->NewStringUTF(key);
  auto* jr = (jstring)e->CallObjectMethod(host_of(u), m_storeGet, jk);
  e->DeleteLocalRef(jk);
  if (!jr) return -1;
  std::string v = toStr(e, jr);
  e->DeleteLocalRef(jr);
  size_t n = v.size() < cap - 1 ? v.size() : cap - 1;
  std::memcpy(out, v.data(), n);
  out[n] = '\0';
  return (int)v.size();
}
void cb_store_set(void* u, const char* key, const char* value) {
  JNIEnv* e = env();
  jstring jk = e->NewStringUTF(key), jv = e->NewStringUTF(value);
  e->CallVoidMethod(host_of(u), m_storeSet, jk, jv);
  e->DeleteLocalRef(jk);
  e->DeleteLocalRef(jv);
}
void cb_store_erase(void* u, const char* key) {
  JNIEnv* e = env();
  jstring jk = e->NewStringUTF(key);
  e->CallVoidMethod(host_of(u), m_storeErase, jk);
  e->DeleteLocalRef(jk);
}

uint32_t cb_now_ms(void*) {
  timespec ts{};
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}
void cb_random(void*, uint8_t* out, size_t len) {
#if defined(__ANDROID_API__) && __ANDROID_API__ >= 28
  if (getrandom(out, len, 0) >= 0) return;
#endif
  int fd = ::open("/dev/urandom", O_RDONLY);
  if (fd >= 0) {
    size_t done = 0;
    while (done < len) {
      ssize_t n = ::read(fd, out + done, len - done);
      if (n <= 0) break;
      done += (size_t)n;
    }
    ::close(fd);
    if (done < len) std::memset(out + done, 0, len - done);
  } else {
    std::memset(out, 0, len);
  }
}
void cb_log(void*, int level, const char* msg) {
  int prio = ANDROID_LOG_INFO;
  if (level >= (int)hg::LogLevel::Warn) prio = ANDROID_LOG_WARN;
  __android_log_write(prio, LOG_TAG, msg ? msg : "");
}

hgsim_host g_host = [] {
  hgsim_host h{};
  h.user = nullptr;
  h.transport_connect = cb_connect;
  h.transport_send_text = [](void* u, const char* d, size_t n) { return cb_send_text(u, d, n); };
  h.transport_send_binary = [](void* u, const uint8_t* d, size_t n) { return cb_send(u, (const char*)d, n); };
  h.transport_close = cb_close;
  h.display_flush = cb_flush;
  h.display_backlight = nullptr;
  h.mic_start = cb_mic_start;
  h.mic_stop = cb_mic_stop;
  h.speaker_begin = cb_spk_begin;
  h.speaker_write = cb_spk_write;
  h.speaker_end = cb_spk_end;
  h.speaker_abort = cb_spk_abort;
  h.speaker_busy = cb_spk_busy;
  h.speaker_volume = cb_spk_volume;
  h.storage_get = cb_store_get;
  h.storage_set = cb_store_set;
  h.storage_erase = cb_store_erase;
  h.now_ms = cb_now_ms;
  h.random_bytes = cb_random;
  h.log = cb_log;
  // update_* stay null: no OTA on Android (the APK itself is the update).
  return h;
}();

void init_host_methods(JNIEnv* e) {
  if (m_connect) return;
  if (!g_host_cls) {
    jclass cls = e->FindClass("com/freaklair/hermesgadget/NativeHost");
    if (!cls) return;
    g_host_cls = (jclass)e->NewGlobalRef(cls);
  }
  m_connect = e->GetMethodID(g_host_cls, "onTransportConnect", "(Ljava/lang/String;Ljava/lang/String;)V");
  m_send = e->GetMethodID(g_host_cls, "onTransportSend", "([B)Z");
  if (jthrowable ex = e->ExceptionOccurred()) { e->ExceptionClear(); }
  m_sendText = e->GetMethodID(g_host_cls, "onTransportSendText", "(Ljava/lang/String;)Z");
  if (jthrowable ex = e->ExceptionOccurred()) { e->ExceptionClear(); }
  m_close = e->GetMethodID(g_host_cls, "onTransportClose", "()V");
  m_flush = e->GetMethodID(g_host_cls, "onDisplayFlush", "(II)V");
  m_micStart = e->GetMethodID(g_host_cls, "onMicStart", "(I)Z");
  m_micStop = e->GetMethodID(g_host_cls, "onMicStop", "()V");
  m_spkBegin = e->GetMethodID(g_host_cls, "onSpeakerBegin", "(I)Z");
  m_spkWrite = e->GetMethodID(g_host_cls, "onSpeakerWrite", "([S)V");
  m_spkAbort = e->GetMethodID(g_host_cls, "onSpeakerAbort", "()V");
  m_spkBusy = e->GetMethodID(g_host_cls, "onSpeakerBusy", "()Z");
  m_spkVolume = e->GetMethodID(g_host_cls, "onSpeakerVolume", "(I)V");
  m_storeGet = e->GetMethodID(g_host_cls, "onStorageGet", "(Ljava/lang/String;)Ljava/lang/String;");
  m_storeSet = e->GetMethodID(g_host_cls, "onStorageSet", "(Ljava/lang/String;Ljava/lang/String;)V");
  m_storeErase = e->GetMethodID(g_host_cls, "onStorageErase", "(Ljava/lang/String;)V");
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
  g_vm = vm;
  return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeCreateOn(JNIEnv* e, jobject /*thiz*/,
                                                            jobject host,
                                                            jstring serverUrl, jstring token,
                                                            jint width, jint height,
                                                            jint hasMic, jint hasSpeaker,
                                                            jlong micRate, jlong speakerRate,
                                                            jstring name) {
  if (!host) {
    // legacy path: bind to NativeHost.INSTANCE, shared by all unbound instances
    if (!g_host_obj) {
      jclass cls = e->FindClass("com/freaklair/hermesgadget/NativeHost");
      if (!cls) return 0;
      g_host_cls = (jclass)e->NewGlobalRef(cls);
      jfieldID inst = e->GetStaticFieldID(g_host_cls, "INSTANCE",
                                          "Lcom/freaklair/hermesgadget/NativeHost;");
      jobject local = inst ? e->GetStaticObjectField(g_host_cls, inst) : nullptr;
      if (!local) {
        jmethodID ctor2 = e->GetMethodID(g_host_cls, "<init>", "()V");
        local = ctor2 ? e->NewObject(g_host_cls, ctor2) : nullptr;
      }
      if (!local) return 0;
      g_host_obj = e->NewGlobalRef(local);
    }
  }
  init_host_methods(e);  // idempotent; bound instances also need the method ids
  // per-instance host record (global ref to this instance's Kotlin NativeHost)
  HostCtx* ctx = new HostCtx{};
  ctx->obj = host ? e->NewGlobalRef(host)
                  : (g_host_obj ? (jobject)(e->NewGlobalRef(g_host_obj)) : nullptr);
  if (!ctx->obj) { delete ctx; return 0; }

  hgsim_config cfg{};
  cfg.width = (int)width;
  cfg.height = (int)height;
  cfg.has_mic = hasMic;
  cfg.has_speaker = hasSpeaker;
  cfg.mic_rate = (uint32_t)micRate;
  cfg.speaker_rate = (uint32_t)speakerRate;
  cfg.board = "android";
  cfg.firmware = "1.0.0-android";
  std::string surl = toStr(e, serverUrl), stoken = toStr(e, token), sname = toStr(e, name);
  cfg.default_server_url = surl.c_str();
  cfg.default_access_token = stoken.c_str();
  cfg.default_name = sname.c_str();
  cfg.talk_label = "HOLD";
  cfg.cancel_label = "SWIPE";
  cfg.touch = 1;
  hgsim_host hlocal = g_host;
  hlocal.user = ctx;
  hgsim* sim = hgsim_create(&cfg, &hlocal);
  if (!sim) { e->DeleteGlobalRef(ctx->obj); delete ctx; return 0; }
  {
    std::lock_guard<std::mutex> lk(g_map_mu);
    g_ctx[sim] = ctx;
  }
  hgsim_begin(sim);
  return (jlong)(intptr_t)sim;
}

JNIEXPORT jlong JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeCreate(JNIEnv* e, jobject thiz,
                                                          jstring serverUrl, jstring token,
                                                          jint width, jint height,
                                                          jint hasMic, jint hasSpeaker,
                                                          jlong micRate, jlong speakerRate,
                                                          jstring name) {
  return Java_com_freaklair_hermesgadget_NativeBridge_nativeCreateOn(
      e, thiz, nullptr, serverUrl, token, width, height, hasMic, hasSpeaker, micRate,
      speakerRate, name);
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeDestroy(JNIEnv* e, jobject, jlong ptr) {
  if (!ptr) return;
  hgsim* sim = (hgsim*)ptr;
  HostCtx* ctx = nullptr;
  {
    std::lock_guard<std::mutex> lk(g_map_mu);
    auto it = g_ctx.find(sim);
    if (it != g_ctx.end()) { ctx = it->second; g_ctx.erase(it); }
  }
  hgsim_destroy(sim);  // last core call; callbacks may fire until here
  if (ctx) {
    if (ctx->obj) env()->DeleteGlobalRef(ctx->obj);
    delete ctx;
  }
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTick(JNIEnv*, jobject, jlong ptr) {
  if (!ptr) return;
  hgsim_tick((hgsim*)ptr);
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeNetwork(JNIEnv*, jobject, jlong ptr, jint up) {
  if (!ptr) return;
  hgsim_network((hgsim*)ptr, up, up ? "android-net" : "offline");
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTransportOpen(JNIEnv*, jobject, jlong ptr) {
  if (!ptr) return;
  hgsim_transport_open((hgsim*)ptr);
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTransportText(JNIEnv* e, jobject, jlong ptr,
                                                                 jstring text) {
  if (!ptr) return;
  std::string s = toStr(e, text);
  hgsim_transport_text((hgsim*)ptr, s.data(), s.size());
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTransportBinary(JNIEnv* e, jobject, jlong ptr,
                                                                   jbyteArray data) {
  if (!ptr) return;
  jsize n = e->GetArrayLength(data);
  jbyte* b = e->GetByteArrayElements(data, nullptr);
  hgsim_transport_binary((hgsim*)ptr, (const uint8_t*)b, (size_t)n);
  e->ReleaseByteArrayElements(data, b, JNI_ABORT);
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTransportClosed(JNIEnv* e, jobject, jlong ptr,
                                                                   jstring reason) {
  if (!ptr) return;
  std::string s = toStr(e, reason);
  hgsim_transport_closed((hgsim*)ptr, s.c_str());
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeButton(JNIEnv*, jobject, jlong ptr, jint b, jint pressed) {
  if (!ptr) return;
  hgsim_button((hgsim*)ptr, b, pressed);
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeTouch(JNIEnv*, jobject, jlong ptr,
                                                         jint touching, jint x, jint y) {
  if (!ptr) return;
  hgsim_touch((hgsim*)ptr, touching, x, y);
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeMicSamples(JNIEnv* e, jobject, jlong ptr,
                                                              jshortArray samples) {
  if (!ptr) return;
  jsize n = e->GetArrayLength(samples);
  jshort* s = e->GetShortArrayElements(samples, nullptr);
  hgsim_mic_samples((hgsim*)ptr, (const int16_t*)s, (size_t)n);
  e->ReleaseShortArrayElements(samples, s, JNI_ABORT);
}
JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeSubmitText(JNIEnv* e, jobject, jlong ptr,
                                                              jstring text) {
  if (!ptr) return;
  std::string s = toStr(e, text);
  hgsim_submit_text((hgsim*)ptr, s.c_str());
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeSetSensor(JNIEnv* e, jobject, jlong ptr,
                                                             jstring name, jdouble value) {
  if (!ptr) return;
  std::string n = toStr(e, name);
  hgsim_set_sensor((hgsim*)ptr, n.c_str(), value);
}

JNIEXPORT void JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeEmitEvent(JNIEnv* e, jobject, jlong ptr,
                                                             jstring name, jstring dataJson,
                                                             jint notify) {
  if (!ptr) return;
  std::string n = toStr(e, name), d = toStr(e, dataJson);
  hgsim_emit_event((hgsim*)ptr, n.c_str(), d.c_str(), notify);
}

JNIEXPORT jstring JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeStatus(JNIEnv*, jobject, jlong ptr) {
  if (!ptr) return env()->NewStringUTF("{}");
  char buf[16384];
  hgsim_status((hgsim*)ptr, buf, sizeof(buf));
  return env()->NewStringUTF(buf);
}

// Rows [y0,y1) of the core framebuffer as ARGB8888 rows; empty array when headless.
JNIEXPORT jobjectArray JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeRows(JNIEnv*, jobject, jlong ptr,
                                                        jint y0, jint y1) {
  if (!ptr) {
    return env()->NewObjectArray(0, env()->FindClass("[I"), nullptr);
  }
  int w = 0, h = 0;
  const uint16_t* fb = hgsim_framebuffer((hgsim*)ptr, &w, &h);
  JNIEnv* e = env();
  if (!fb || !w || !h) return e->NewObjectArray(0, e->FindClass("[I"), nullptr);
  if (y0 < 0) y0 = 0;
  if (y1 > h) y1 = h;
  jclass intArr = e->FindClass("[I");
  jobjectArray rows = e->NewObjectArray(y1 - y0, intArr, nullptr);
  for (int y = y0; y < y1; ++y) {
    jintArray row = e->NewIntArray(w);
    if (!row) continue;
    std::vector<int> tmp((size_t)w);
    for (int x = 0; x < w; ++x) {
      uint16_t p = fb[(size_t)y * w + x];
      int r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
      tmp[(size_t)x] = (int)(0xFF000000u | ((r * 255 + 15) / 31 << 16) | ((g * 255 + 31) / 63 << 8) | ((b * 255 + 15) / 31));
    }
    e->SetIntArrayRegion(row, 0, w, tmp.data());
    e->SetObjectArrayElement(rows, y - y0, row);
    e->DeleteLocalRef(row);
  }
  return rows;
}

JNIEXPORT jintArray JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeSize(JNIEnv*, jobject, jlong ptr) {
  if (!ptr) {
    JNIEnv* e0 = env();
    jintArray z = e0->NewIntArray(2);
    jint dims0[2] = {0, 0};
    e0->SetIntArrayRegion(z, 0, 2, dims0);
    return z;
  }
  int w = 0, h = 0;
  hgsim_framebuffer((hgsim*)ptr, &w, &h);
  JNIEnv* e = env();
  jintArray out = e->NewIntArray(2);
  jint dims[2] = {w, h};
  e->SetIntArrayRegion(out, 0, 2, dims);
  return out;
}

JNIEXPORT jstring JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeScreen(JNIEnv* e, jobject, jlong ptr) {
  if (!ptr) return e->NewStringUTF("");
  const char* s = hgsim_screen((hgsim*)ptr);
  return e->NewStringUTF(s ? s : "");
}

JNIEXPORT jstring JNICALL
Java_com_freaklair_hermesgadget_NativeBridge_nativeConsole(JNIEnv* e, jobject, jlong ptr,
                                                           jstring line) {
  if (!ptr) return e->NewStringUTF("");
  std::string s = toStr(e, line);
  std::vector<char> buf(65536);
  int n = hgsim_console((hgsim*)ptr, s.c_str(), buf.data(), buf.size());
  if (n < 0) n = 0;
  if ((size_t)n > buf.size() - 1) n = (int)buf.size() - 1;
  return e->NewStringUTF(std::string(buf.data(), (size_t)n).c_str());
}

}  // extern "C"
