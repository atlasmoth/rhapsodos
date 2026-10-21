#include <jni.h>
#include <dlfcn.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "ptts.h"

static void *runtime;
static PttsHandle *(*open_model)(const char *, uint32_t, const char *);
static bool (*speak)(PttsHandle *, const char *, PttsFrameFn, void *, PttsResult *);
static void (*free_model)(PttsHandle *);
static bool (*set_voice)(PttsHandle *, const char *);

typedef struct {
    JNIEnv *env;
    jobject cancellation;
    jmethodID check;
    float *pcm;
    size_t count;
    size_t capacity;
    double deadline;
    const char *failure;
} AudioBuffer;

static double monotonic_seconds(void) {
    struct timespec time;
    clock_gettime(CLOCK_MONOTONIC, &time);
    return (double)time.tv_sec + (double)time.tv_nsec / 1e9;
}

static void fail(JNIEnv *env, const char *message) {
    if ((*env)->ExceptionCheck(env)) return;
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type) (*env)->ThrowNew(env, type, message);
}

static char *utf8(JNIEnv *env, jbyteArray value, jsize limit) {
    jsize size = (*env)->GetArrayLength(env, value);
    if (size < 1 || size > limit) { fail(env, "Invalid offline voice input."); return NULL; }
    char *text = malloc((size_t)size + 1);
    if (!text) { fail(env, "There is not enough memory to prepare this voice."); return NULL; }
    (*env)->GetByteArrayRegion(env, value, 0, size, (jbyte *)text);
    if ((*env)->ExceptionCheck(env)) { free(text); return NULL; }
    if (memchr(text, 0, (size_t)size)) { free(text); fail(env, "Invalid offline voice input."); return NULL; }
    text[size] = 0;
    return text;
}

static bool collect(const float *pcm, size_t count, void *user) {
    AudioBuffer *buffer = user;
    JNIEnv *env = buffer->env;
    (*env)->CallVoidMethod(env, buffer->cancellation, buffer->check);
    if ((*env)->ExceptionCheck(env)) return false;
    if (monotonic_seconds() > buffer->deadline) {
        buffer->failure = "This voice took too long to prepare the passage. Try a shorter passage.";
        return false;
    }
    const size_t maximum = 24000 * 180;
    if (count > maximum - buffer->count) {
        buffer->failure = "This voice produced an overlong passage. Use a shorter passage.";
        return false;
    }
    for (size_t i = 0; i < count; i++) {
        if (!isfinite(pcm[i])) { buffer->failure = "This voice produced invalid audio."; return false; }
    }
    size_t needed = buffer->count + count;
    if (needed > buffer->capacity) {
        size_t capacity = buffer->capacity ? buffer->capacity * 2 : 24000;
        if (capacity < needed) capacity = needed;
        if (capacity > maximum) capacity = maximum;
        float *resized = realloc(buffer->pcm, capacity * sizeof(float));
        if (!resized) { buffer->failure = "There is not enough memory to prepare this voice."; return false; }
        buffer->pcm = resized;
        buffer->capacity = capacity;
    }
    if (count) memcpy(buffer->pcm + buffer->count, pcm, count * sizeof(float));
    buffer->count = needed;
    return true;
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    runtime = dlopen("libptts_ffi.so", RTLD_NOW | RTLD_LOCAL);
    if (!runtime) return JNI_ERR;
    open_model = dlsym(runtime, "ptts_new");
    speak = dlsym(runtime, "ptts_speak");
    free_model = dlsym(runtime, "ptts_free");
    set_voice = dlsym(runtime, "ptts_set_voice");
    if (!open_model || !speak || !free_model || !set_voice) { dlclose(runtime); runtime = NULL; return JNI_ERR; }
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_com_osuji_ereaderapp_speech_PhononNative_open(JNIEnv *env, jobject owner, jbyteArray path) {
    (void)owner;
    char *directory = utf8(env, path, 16384);
    if (!directory) return 0;
    setenv("RAYON_NUM_THREADS", "2", 1);
    PttsHandle *handle = open_model(directory, PTTS_UNIT_CPU, "en");
    free(directory);
    if (handle && !set_voice(handle, "default")) { free_model(handle); handle = NULL; }
    if (!handle) fail(env, "This voice could not be loaded. Check or download the pack again.");
    return (jlong)(intptr_t)handle;
}

JNIEXPORT jfloatArray JNICALL Java_com_osuji_ereaderapp_speech_PhononNative_synthesize(JNIEnv *env, jobject owner, jlong value, jbyteArray input, jbyteArray voice, jobject cancellation) {
    (void)owner;
    if (!value) { fail(env, "This offline voice is closed."); return NULL; }
    char *text = utf8(env, input, 8000);
    if (!text) return NULL;
    char *name = utf8(env, voice, 96);
    if (!name) { free(text); return NULL; }
    bool selected = set_voice((PttsHandle *)(intptr_t)value, name);
    free(name);
    if (!selected) { free(text); fail(env, "This voice is not in the installed pack."); return NULL; }
    jclass type = (*env)->GetObjectClass(env, cancellation);
    if (!type) { free(text); return NULL; }
    jmethodID check = (*env)->GetMethodID(env, type, "run", "()V");
    (*env)->DeleteLocalRef(env, type);
    if (!check) { free(text); return NULL; }
    AudioBuffer buffer = {.env = env, .cancellation = cancellation, .check = check, .deadline = monotonic_seconds() + 120};
    PttsResult result = {0};
    bool complete = speak((PttsHandle *)(intptr_t)value, text, collect, &buffer, &result);
    free(text);
    jfloatArray output = NULL;
    if (!(*env)->ExceptionCheck(env)) {
        if (buffer.failure) fail(env, buffer.failure);
        else if (!complete || !buffer.count || result.samples != buffer.count) fail(env, "This voice could not prepare the complete passage. Try again or choose another voice.");
        else {
            output = (*env)->NewFloatArray(env, (jsize)buffer.count);
            if (output) (*env)->SetFloatArrayRegion(env, output, 0, (jsize)buffer.count, buffer.pcm);
        }
    }
    free(buffer.pcm);
    return output;
}

JNIEXPORT void JNICALL Java_com_osuji_ereaderapp_speech_PhononNative_close(JNIEnv *env, jobject owner, jlong value) {
    (void)env; (void)owner;
    if (value) free_model((PttsHandle *)(intptr_t)value);
}
