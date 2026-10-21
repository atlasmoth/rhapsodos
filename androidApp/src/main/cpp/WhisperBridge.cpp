#include <jni.h>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <chrono>
#include <string>
#include <vector>
#include <new>
#include <pthread.h>
#include <atomic>
#include <exception>
#include "whisper.h"

static void fail(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) env->ThrowNew(type, message);
}
static void quiet_log(enum ggml_log_level, const char *, void *) {}
struct Cancellation {
    JNIEnv *env;
    jobject callback;
    jmethodID check;
    std::chrono::steady_clock::time_point deadline;
    bool expired = false;
    pthread_t thread = pthread_self();
    std::atomic<bool> unexpectedThread{false};
};
static bool cancelled(void *value) {
    auto *state = static_cast<Cancellation *>(value);
    if (!pthread_equal(pthread_self(), state->thread)) { state->unexpectedThread = true; return true; }
    if (state->env->ExceptionCheck()) return true;
    state->env->CallVoidMethod(state->callback, state->check);
    state->expired = std::chrono::steady_clock::now() > state->deadline;
    return state->expired || state->env->ExceptionCheck();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_osuji_ereaderapp_speech_WhisperNative_open(JNIEnv *env, jclass, jbyteArray path) {
    try {
        if (!path) { fail(env, "Missing transcription model."); return 0; }
        const jsize size = env->GetArrayLength(path);
        if (size < 1 || size > 16384) { fail(env, "Invalid transcription model path."); return 0; }
        std::string value(static_cast<size_t>(size), '\0');
        env->GetByteArrayRegion(path, 0, size, reinterpret_cast<jbyte *>(value.data()));
        if (env->ExceptionCheck()) return 0;
        if (value.find('\0') != std::string::npos) { fail(env, "Invalid transcription model path."); return 0; }
        whisper_log_set(quiet_log, nullptr);
        ggml_log_set(quiet_log, nullptr);
        auto params = whisper_context_default_params();
        params.use_gpu = false;
        params.flash_attn = false;
        auto *context = whisper_init_from_file_with_params(value.c_str(), params);
        if (!context) fail(env, "The transcription model could not be loaded. Check or download it again.");
        return reinterpret_cast<jlong>(context);
    } catch (const std::bad_alloc &) { fail(env, "There is not enough memory to load transcription."); return 0; }
    catch (const std::exception &) { fail(env, "The transcription model could not be loaded."); return 0; }
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_osuji_ereaderapp_speech_WhisperNative_recognize(JNIEnv *env, jclass, jlong handle, jfloatArray input, jobject callback) {
    try {
        if (!handle || !input || !callback) { fail(env, "The transcription model is unavailable."); return nullptr; }
        const jsize size = env->GetArrayLength(input);
        if (size < 1 || size > 480000) { fail(env, "Invalid transcription window."); return nullptr; }
        std::vector<float> samples(static_cast<size_t>(size));
        env->GetFloatArrayRegion(input, 0, size, samples.data());
        if (env->ExceptionCheck()) return nullptr;
        for (float sample : samples) if (!std::isfinite(sample) || std::fabs(sample) > 1.0f) { fail(env, "The recording contains invalid audio."); return nullptr; }
        jclass type = env->GetObjectClass(callback);
        if (!type || env->ExceptionCheck()) return nullptr;
        jmethodID check = env->GetMethodID(type, "run", "()V");
        env->DeleteLocalRef(type);
        if (!check) return nullptr;
        Cancellation cancellation{env, callback, check, std::chrono::steady_clock::now() + std::chrono::seconds(120)};
        if (cancelled(&cancellation)) return nullptr;
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = 2;
        params.language = "en";
        params.translate = false;
        params.no_context = true;
        // English SOT-only decoding preserves the model's no-speech probability.
        params.no_timestamps = false;
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.temperature = 0.0f;
        params.temperature_inc = 0.0f;
        params.no_speech_thold = 0.6f;
        params.logprob_thold = -1.0f;
        params.greedy.best_of = 1;
        params.abort_callback = cancelled;
        params.abort_callback_user_data = &cancellation;
        auto *context = reinterpret_cast<whisper_context *>(handle);
        const int status = whisper_full(context, params, samples.data(), size);
        if (env->ExceptionCheck()) return nullptr;
        if (cancellation.expired) { fail(env, "Transcription took too long. Try again when this device is idle."); return nullptr; }
        if (cancellation.unexpectedThread) { fail(env, "Transcription could not check cancellation safely."); return nullptr; }
        if (status != 0) { fail(env, "This recording could not be transcribed. Try again."); return nullptr; }
        if (cancelled(&cancellation)) {
            if (cancellation.expired) fail(env, "Transcription took too long. Try again when this device is idle.");
            return nullptr;
        }
        std::string text;
        for (int index = 0; index < whisper_full_n_segments(context); index++) {
            // Confident sound captions are not speech, even when their token confidence is high.
            if (whisper_full_get_segment_no_speech_prob(context, index) > params.no_speech_thold) continue;
            const char *segment = whisper_full_get_segment_text(context, index);
            if (!segment) { fail(env, "The transcription result was incomplete."); return nullptr; }
            text += segment;
            if (text.size() > 65536) { fail(env, "The transcription result exceeded its limit."); return nullptr; }
        }
        auto output = env->NewByteArray(static_cast<jsize>(text.size()));
        if (output) env->SetByteArrayRegion(output, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
        return output;
    } catch (const std::bad_alloc &) { fail(env, "There is not enough memory to transcribe this recording."); return nullptr; }
    catch (const std::exception &) { fail(env, "This recording could not be transcribed. Try again."); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL Java_com_osuji_ereaderapp_speech_WhisperNative_close(JNIEnv *, jclass, jlong handle) {
    if (handle) whisper_free(reinterpret_cast<whisper_context *>(handle));
}
