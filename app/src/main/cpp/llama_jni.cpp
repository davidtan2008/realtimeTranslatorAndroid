#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <ctime>
#include <mutex>
#include <sched.h>
#include <string>
#include <unistd.h>
#include <utility>
#include <vector>

#include "llama.h"

#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, "duiyi_llama", __VA_ARGS__)

static void jlog(const char * fmt, ...) {
    FILE * f = fopen("/data/data/com.duiyi.android/files/llama-jni.log", "a");
    if (f == nullptr) return;
    va_list args;
    va_start(args, fmt);
    vfprintf(f, fmt, args);
    va_end(args);
    fputc('\n', f);
    fclose(f);
}

static void llama_file_log(enum ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_DEBUG) return;
    FILE * f = fopen("/data/data/com.duiyi.android/files/llama-jni.log", "a");
    if (f == nullptr) return;
    fputs(text, f);
    fclose(f);
}

static int64_t now_ms() {
    timespec ts {};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000 + ts.tv_nsec / 1000000;
}

struct PrefixSlot {
    std::vector<llama_token> tokens;
    std::vector<uint8_t> state;
};

struct Handle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    llama_sampler * smpl = nullptr;
    std::atomic<bool> abort{false};
    std::vector<PrefixSlot> prefixes;
};

static std::mutex g_lock;
static bool g_backend = false;
static const int kMaxPrefixes = 2;

static void ensure_backend() {
    if (g_backend) return;
    llama_log_set(llama_file_log, nullptr);
    llama_backend_init();
    g_backend = true;
    const char * info = llama_print_system_info();
    if (info != nullptr) {
        jlog("system %s", info);
    }
}

static bool abort_cb(void * data) {
    return static_cast<Handle *>(data)->abort.load();
}

static long cpu_score(int cpu) {
    char path[160];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpu_capacity", cpu);
    FILE * f = fopen(path, "r");
    if (f != nullptr) {
        long value = 0;
        const int ok = fscanf(f, "%ld", &value);
        fclose(f);
        if (ok == 1) return value;
    }
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    f = fopen(path, "r");
    if (f != nullptr) {
        long value = 0;
        const int ok = fscanf(f, "%ld", &value);
        fclose(f);
        if (ok == 1) return value;
    }
    return cpu;
}

static void pin_to_big_cores(int n_threads) {
    const int ncpu = static_cast<int>(sysconf(_SC_NPROCESSORS_ONLN));
    if (ncpu <= 0 || n_threads <= 0) return;
    std::vector<std::pair<long, int>> cores;
    cores.reserve(ncpu);
    for (int cpu = 0; cpu < ncpu; ++cpu) {
        cores.emplace_back(cpu_score(cpu), cpu);
    }
    std::sort(cores.begin(), cores.end(), [](const auto & a, const auto & b) {
        if (a.first != b.first) return a.first > b.first;
        return a.second > b.second;
    });
    cpu_set_t set;
    CPU_ZERO(&set);
    const int take = std::min(n_threads, ncpu);
    for (int i = 0; i < take; ++i) {
        CPU_SET(cores[i].second, &set);
        jlog("pin thread to cpu=%d score=%ld", cores[i].second, cores[i].first);
    }
    if (sched_setaffinity(0, sizeof(set), &set) != 0) {
        jlog("sched_setaffinity failed");
    }
}

static jstring to_jstring(JNIEnv * env, const std::string & utf8) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
    if (bytes == nullptr) return env->NewStringUTF("");
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()), reinterpret_cast<const jbyte *>(utf8.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = static_cast<jstring>(env->NewObject(string_class, ctor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result == nullptr ? env->NewStringUTF("") : result;
}

static std::string jstring_to_utf8(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars == nullptr ? "" : chars;
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return out;
}

static std::string token_piece(const llama_vocab * vocab, llama_token id) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, id, buf, sizeof(buf), 0, true);
    if (n >= 0) {
        return std::string(buf, n);
    }
    const int needed = -n;
    if (needed <= 0) return {};
    std::string out(needed, 0);
    llama_token_to_piece(vocab, id, out.data(), out.size(), 0, true);
    return out;
}

static std::vector<llama_token> tokenize_text(
    const llama_vocab * vocab,
    const std::string & text,
    bool add_special
) {
    if (text.empty()) return {};
    const int n = -llama_tokenize(
        vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> tokens(n);
    if (llama_tokenize(
            vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), tokens.size(), add_special, true) < 0) {
        return {};
    }
    return tokens;
}

static int decode_tokens(Handle * handle, llama_token * tokens, int n_tokens) {
    const int n_ubatch = std::max(1, static_cast<int>(llama_n_ubatch(handle->ctx)));
    for (int i = 0; i < n_tokens; ) {
        if (handle->abort.load()) {
            jlog("decode aborted at %d/%d", i, n_tokens);
            return 2;
        }
        const int n = std::min(n_ubatch, n_tokens - i);
        llama_batch batch = llama_batch_get_one(tokens + i, n);
        const int decoded = llama_decode(handle->ctx, batch);
        if (decoded != 0) {
            jlog("decode failed at %d/%d n=%d code=%d", i, n_tokens, n, decoded);
            return decoded;
        }
        i += n;
    }
    return 0;
}

static bool snapshot_state(Handle * handle, PrefixSlot & slot) {
    const size_t need = llama_state_get_size(handle->ctx);
    if (need == 0) return false;
    slot.state.resize(need);
    const size_t written = llama_state_get_data(handle->ctx, slot.state.data(), slot.state.size());
    if (written == 0 || written > need) {
        slot.state.clear();
        return false;
    }
    slot.state.resize(written);
    return true;
}

static bool restore_state(Handle * handle, const PrefixSlot & slot) {
    if (slot.state.empty()) return false;
    const size_t read = llama_state_set_data(handle->ctx, slot.state.data(), slot.state.size());
    return read > 0;
}

static PrefixSlot * find_prefix(Handle * handle, const std::vector<llama_token> & tokens) {
    for (auto it = handle->prefixes.rbegin(); it != handle->prefixes.rend(); ++it) {
        if (it->tokens == tokens) return &(*it);
    }
    return nullptr;
}

static bool ensure_prefix(Handle * handle, const std::vector<llama_token> & prefix_tokens) {
    if (prefix_tokens.empty()) {
        llama_memory_clear(llama_get_memory(handle->ctx), true);
        return true;
    }
    PrefixSlot * hit = find_prefix(handle, prefix_tokens);
    if (hit != nullptr && restore_state(handle, *hit)) {
        jlog("prefix cache hit tokens=%zu state=%zu", prefix_tokens.size(), hit->state.size());
        return true;
    }
    llama_memory_clear(llama_get_memory(handle->ctx), true);
    const int64_t t0 = now_ms();
    if (decode_tokens(handle, const_cast<llama_token *>(prefix_tokens.data()), static_cast<int>(prefix_tokens.size())) != 0) {
        return false;
    }
    PrefixSlot slot;
    slot.tokens = prefix_tokens;
    if (!snapshot_state(handle, slot)) {
        jlog("prefix snapshot failed");
        return false;
    }
    jlog("prefix cache store tokens=%zu state=%zu ms=%lld",
         slot.tokens.size(), slot.state.size(), static_cast<long long>(now_ms() - t0));
    handle->prefixes.erase(
        std::remove_if(handle->prefixes.begin(), handle->prefixes.end(),
            [&](const PrefixSlot & existing) { return existing.tokens == prefix_tokens; }),
        handle->prefixes.end());
    if (static_cast<int>(handle->prefixes.size()) >= kMaxPrefixes) {
        handle->prefixes.erase(handle->prefixes.begin());
    }
    handle->prefixes.push_back(std::move(slot));
    return true;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_duiyi_android_services_translation_LlamaNative_create(
    JNIEnv * env,
    jobject,
    jstring jpath,
    jint n_ctx,
    jint n_threads
) {
    std::lock_guard<std::mutex> lock(g_lock);
    ensure_backend();
    pin_to_big_cores(n_threads);
    const std::string path = jstring_to_utf8(env, jpath);
    auto * handle = new Handle();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    ALOGE("create loading ctx=%d threads=%d", n_ctx, n_threads);
    jlog("create loading ctx=%d threads=%d path=%s", n_ctx, n_threads, path.c_str());
    handle->model = llama_model_load_from_file(path.c_str(), mparams);
    if (handle->model == nullptr) {
        ALOGE("unable to load model");
        jlog("unable to load model");
        delete handle;
        return 0;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = n_ctx;
    cparams.n_batch = 64;
    cparams.n_ubatch = 64;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.offload_kqv = false;
    cparams.op_offload = false;
    handle->ctx = llama_init_from_model(handle->model, cparams);
    if (handle->ctx == nullptr) {
        ALOGE("unable to create context");
        jlog("unable to create context");
        llama_model_free(handle->model);
        delete handle;
        return 0;
    }
    auto sparams = llama_sampler_chain_default_params();
    handle->smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(handle->smpl, llama_sampler_init_greedy());
    llama_set_abort_callback(handle->ctx, abort_cb, handle);

    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    llama_token bos = llama_vocab_bos(vocab);
    if (bos != LLAMA_TOKEN_NULL) {
        jlog("warmup decode start bos=%d", bos);
        const int warm = decode_tokens(handle, &bos, 1);
        jlog("warmup decode code=%d", warm);
        llama_memory_clear(llama_get_memory(handle->ctx), true);
        llama_sampler_reset(handle->smpl);
    }
    ALOGE("create ok");
    jlog("create ok n_batch=%u n_ubatch=%u n_ctx=%u",
         llama_n_batch(handle->ctx), llama_n_ubatch(handle->ctx), llama_n_ctx(handle->ctx));
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_duiyi_android_services_translation_LlamaNative_warmPrefix(
    JNIEnv * env,
    jobject,
    jlong handle_ptr,
    jstring jprefix
) {
    auto * handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr || handle->ctx == nullptr || handle->model == nullptr) return;
    std::lock_guard<std::mutex> lock(g_lock);
    handle->abort.store(false);
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const std::string prefix = jstring_to_utf8(env, jprefix);
    const auto prefix_tokens = tokenize_text(vocab, prefix, true);
    if (prefix_tokens.empty()) return;
    llama_sampler_reset(handle->smpl);
    ensure_prefix(handle, prefix_tokens);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_duiyi_android_services_translation_LlamaNative_generate(
    JNIEnv * env,
    jobject,
    jlong handle_ptr,
    jstring jprefix,
    jstring jsuffix,
    jint max_tokens
) {
    auto * handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr || handle->ctx == nullptr || handle->model == nullptr) {
        return env->NewStringUTF("");
    }
    std::lock_guard<std::mutex> lock(g_lock);
    handle->abort.store(false);
    llama_sampler_reset(handle->smpl);

    const std::string prefix = jstring_to_utf8(env, jprefix);
    const std::string suffix = jstring_to_utf8(env, jsuffix);
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    const int64_t t0 = now_ms();

    std::vector<llama_token> prefix_tokens = tokenize_text(vocab, prefix, true);
    std::vector<llama_token> suffix_tokens;
    const std::string full = prefix + suffix;
    const auto full_tokens = tokenize_text(vocab, full, true);
    if (!prefix_tokens.empty() &&
        full_tokens.size() >= prefix_tokens.size() &&
        std::equal(prefix_tokens.begin(), prefix_tokens.end(), full_tokens.begin())) {
        suffix_tokens.assign(full_tokens.begin() + static_cast<long>(prefix_tokens.size()), full_tokens.end());
    } else {
        jlog("prefix token mismatch prefix=%zu full=%zu, decoding whole prompt",
             prefix_tokens.size(), full_tokens.size());
        prefix_tokens.clear();
        suffix_tokens = full_tokens;
    }
    if (suffix_tokens.empty()) {
        jlog("empty suffix");
        return env->NewStringUTF("");
    }
    const int n_ctx = static_cast<int>(llama_n_ctx(handle->ctx));
    if (static_cast<int>(prefix_tokens.size() + suffix_tokens.size()) >= n_ctx - 8) {
        jlog("prompt too long prefix=%zu suffix=%zu ctx=%d", prefix_tokens.size(), suffix_tokens.size(), n_ctx);
        return env->NewStringUTF("");
    }
    if (!ensure_prefix(handle, prefix_tokens)) {
        ALOGE("prefix decode failed");
        return env->NewStringUTF("");
    }
    const int64_t t_prefix = now_ms();
    if (decode_tokens(handle, suffix_tokens.data(), static_cast<int>(suffix_tokens.size())) != 0) {
        ALOGE("suffix decode failed");
        return env->NewStringUTF("");
    }
    const int64_t t_suffix = now_ms();

    std::string out;
    const int limit = max_tokens > 0 ? max_tokens : 128;
    for (int i = 0; i < limit; ++i) {
        if (handle->abort.load()) break;
        const llama_token id = llama_sampler_sample(handle->smpl, handle->ctx, -1);
        if (i == 0) {
            ALOGE("first token=%d eog=%d", id, llama_vocab_is_eog(vocab, id) ? 1 : 0);
            jlog("first token=%d eog=%d", id, llama_vocab_is_eog(vocab, id) ? 1 : 0);
        }
        if (llama_vocab_is_eog(vocab, id)) break;
        out += token_piece(vocab, id);
        llama_token next = id;
        if (decode_tokens(handle, &next, 1) != 0) break;
    }
    jlog("generate prefix=%zu suffix=%zu out=%zu prefix_ms=%lld suffix_ms=%lld gen_ms=%lld",
         prefix_tokens.size(), suffix_tokens.size(), out.size(),
         static_cast<long long>(t_prefix - t0),
         static_cast<long long>(t_suffix - t_prefix),
         static_cast<long long>(now_ms() - t_suffix));
    return to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_com_duiyi_android_services_translation_LlamaNative_abort(
    JNIEnv *,
    jobject,
    jlong handle_ptr
) {
    auto * handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr) return;
    handle->abort.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_duiyi_android_services_translation_LlamaNative_destroy(
    JNIEnv *,
    jobject,
    jlong handle_ptr
) {
    std::lock_guard<std::mutex> lock(g_lock);
    auto * handle = reinterpret_cast<Handle *>(handle_ptr);
    if (handle == nullptr) return;
    handle->abort.store(true);
    handle->prefixes.clear();
    if (handle->smpl) llama_sampler_free(handle->smpl);
    if (handle->ctx) llama_free(handle->ctx);
    if (handle->model) llama_model_free(handle->model);
    delete handle;
}
