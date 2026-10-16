// 本地向量化 / 重排推理的 JNI 实现：llama.cpp + GGUF，CPU 单机推理。
//
// 对外只暴露两个能力：
//   nativeEmbed  —— 一段或多段文本 -> 归一化向量（bge-small-zh-v1.5 等 embedding 模型）
//   nativeRerank —— query + 若干候选文本 -> 每个候选的相关性分数（bge-reranker-base）
//
// 模型与上下文常驻 native 侧，用 handle（jlong）引用；加载失败返回 0，绝不抛异常到 Java 层。

#include <jni.h>
#include <android/log.h>

#include "llama.h"

#include <cmath>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "lwq_embed"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 正文块按 400 字左右切分，加上 query/文档拼接，512 上下文足够；批量上限与上下文一致
constexpr uint32_t kCtxSize = 512;
// 一次 decode 最多并行的段数（KV 复用同一块内存）
constexpr int32_t kSeqMax = 8;

struct ModelHandle {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    const llama_vocab * vocab = nullptr;
    int32_t n_embd = 0;                 // 向量维度（embedding 模型）
    int32_t n_cls = 1;                  // 分类头输出数（rerank 模型通常为 1）
    // llama.h 里 llama_pooling_type 既是枚举类型又是取值函数，声明字段时必须带 enum 标签
    enum llama_pooling_type pooling = LLAMA_POOLING_TYPE_NONE;
    std::string rerank_template;        // rerank 模型自带的 chat 模板（{query}/{document}）
    std::mutex mtx;                     // llama_context 非线程安全，串行化调用
};

std::once_flag g_backend_once;

void ensure_backend() {
    std::call_once(g_backend_once, [] { llama_backend_init(); });
}

int32_t worker_threads() {
    unsigned n = std::thread::hardware_concurrency();
    if (n == 0) n = 2;
    if (n > 4) n = 4;
    return (int32_t) n;
}

void l2_normalize(float * v, int32_t n) {
    double sum = 0.0;
    for (int32_t i = 0; i < n; ++i) sum += (double) v[i] * (double) v[i];
    if (sum <= 0.0) return;
    const float inv = (float) (1.0 / std::sqrt(sum));
    for (int32_t i = 0; i < n; ++i) v[i] *= inv;
}

void replace_all(std::string & s, const std::string & from, const std::string & to) {
    if (from.empty()) return;
    size_t pos = 0;
    while ((pos = s.find(from, pos)) != std::string::npos) {
        s.replace(pos, from.size(), to);
        pos += to.size();
    }
}

// 分词；超出上下文一律截断到 kCtxSize
std::vector<llama_token> tokenize_seq(const ModelHandle * h, const std::string & text) {
    std::vector<llama_token> out(text.size() + 8);
    int32_t n = llama_tokenize(h->vocab, text.c_str(), (int32_t) text.size(),
                               out.data(), (int32_t) out.size(), true, true);
    if (n < 0) {  // 缓冲区不够：返回值是所需长度，扩容重试
        out.resize((size_t) -n);
        n = llama_tokenize(h->vocab, text.c_str(), (int32_t) text.size(),
                           out.data(), (int32_t) out.size(), true, true);
    }
    if (n < 0) return {};
    out.resize((size_t) n);
    if ((int32_t) out.size() > (int32_t) kCtxSize) out.resize(kCtxSize);
    return out;
}

// 把若干段序列批量送入 decode，输出每段的池化向量（按 seqs 顺序，行宽 n_embd）
bool run_sequences(ModelHandle * h, const std::vector<std::vector<llama_token>> & seqs,
                   std::vector<float> & out) {
    const int32_t n_seq = (int32_t) seqs.size();
    out.assign((size_t) n_seq * (size_t) h->n_embd, 0.0f);

    auto flush = [&](int32_t from, int32_t count) -> bool {
        int32_t total = 0;
        for (int32_t i = from; i < from + count; ++i) total += (int32_t) seqs[i].size();
        if (total == 0) return true;   // 无有效 token：保持零向量

        llama_memory_clear(llama_get_memory(h->ctx), true);
        llama_batch batch = llama_batch_init(total, 0, 1);
        int32_t idx = 0;
        for (int32_t s = 0; s < count; ++s) {
            const std::vector<llama_token> & toks = seqs[from + s];
            const int32_t n = (int32_t) toks.size();
            for (int32_t i = 0; i < n; ++i) {
                batch.token[idx] = toks[i];
                batch.pos[idx] = (llama_pos) i;
                batch.n_seq_id[idx] = 1;
                batch.seq_id[idx][0] = (llama_seq_id) s;
                batch.logits[idx] = (i + 1 == n) ? 1 : 0;   // 池化只需末位产出
                ++idx;
            }
        }
        batch.n_tokens = idx;
        const int32_t rc = llama_decode(h->ctx, batch);
        bool ok = (rc == 0);
        if (!ok) {
            LOGE("llama_decode failed: rc=%d", rc);
        } else {
            const bool is_rank = (h->pooling == LLAMA_POOLING_TYPE_RANK);
            const int32_t cols = is_rank ? h->n_cls : h->n_embd;
            for (int32_t s = 0; s < count && ok; ++s) {
                const float * e = llama_get_embeddings_seq(h->ctx, (llama_seq_id) s);
                if (e == nullptr) {
                    LOGE("no embedding for seq %d", s);
                    ok = false;
                    break;
                }
                float * dst = out.data() + (size_t) (from + s) * (size_t) h->n_embd;
                for (int32_t c = 0; c < cols; ++c) dst[c] = e[c];
                if (!is_rank) l2_normalize(dst, h->n_embd);
            }
        }
        llama_batch_free(batch);
        return ok;
    };

    int32_t base = 0;           // 当前批次首段下标
    int32_t used = 0;           // 当前批次已占用 token 数
    int32_t count = 0;          // 当前批次段数
    for (int32_t i = 0; i < n_seq; ++i) {
        const int32_t n = (int32_t) seqs[i].size();
        if (count > 0 && (count >= kSeqMax || used + n > (int32_t) kCtxSize)) {
            if (!flush(base, count)) return false;
            base = i;
            used = 0;
            count = 0;
        }
        used += n;
        ++count;
    }
    if (count > 0 && !flush(base, count)) return false;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lianwenqi_app_ai_LocalEmbeddingEngine_nativeLoadModel(
        JNIEnv * env, jobject /*this*/, jstring jpath, jboolean jrerank) {
    ensure_backend();

    const bool is_rerank = (jrerank == JNI_TRUE);

    const char * cpath = env->GetStringUTFChars(jpath, nullptr);
    std::string path = (cpath != nullptr) ? cpath : "";
    env->ReleaseStringUTFChars(jpath, cpath);
    if (path.empty()) return 0;

    auto * h = new ModelHandle();

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;   // Android 端只用 CPU 后端
    h->model = llama_model_load_from_file(path.c_str(), mp);
    if (h->model == nullptr) {
        LOGE("load model failed: %s", path.c_str());
        delete h;
        return 0;
    }

    h->vocab = llama_model_get_vocab(h->model);
    h->n_embd = (int32_t) llama_model_n_embd_out(h->model);
    h->n_cls = (int32_t) llama_model_n_cls_out(h->model);
    if (h->n_cls <= 0) h->n_cls = 1;

    const char * tmpl = llama_model_chat_template(h->model, "rerank");
    if (tmpl != nullptr) h->rerank_template = tmpl;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = kCtxSize;
    cp.n_batch = kCtxSize;
    cp.n_ubatch = kCtxSize;
    cp.n_seq_max = kSeqMax;
    cp.n_threads = worker_threads();
    cp.n_threads_batch = worker_threads();
    cp.embeddings = true;
    cp.no_perf = true;
    cp.kv_unified = true;
    // 重排模型的 GGUF 多数不带 pooling_type 元数据，必须显式指定 RANK 才会挂上分类头
    // （llama-server 的 --reranking 也是这么处理的）；向量模型则交给模型元数据决定
    cp.pooling_type = is_rerank ? LLAMA_POOLING_TYPE_RANK : LLAMA_POOLING_TYPE_UNSPECIFIED;
    h->ctx = llama_init_from_model(h->model, cp);
    if (h->ctx == nullptr) {
        LOGE("create context failed: %s", path.c_str());
        llama_model_free(h->model);
        delete h;
        return 0;
    }

    h->pooling = llama_pooling_type(h->ctx);
    LOGI("loaded %s (dim=%d cls=%d pooling=%d template=%s)", path.c_str(),
         h->n_embd, h->n_cls, (int) h->pooling,
         h->rerank_template.empty() ? "no" : "yes");
    return (jlong) (intptr_t) h;
}

extern "C" JNIEXPORT void JNICALL
Java_com_lianwenqi_app_ai_LocalEmbeddingEngine_nativeFreeModel(
        JNIEnv * /*env*/, jobject /*this*/, jlong handle) {
    auto * h = reinterpret_cast<ModelHandle *>((intptr_t) handle);
    if (h == nullptr) return;
    {
        std::lock_guard<std::mutex> lock(h->mtx);
        if (h->ctx != nullptr) llama_free(h->ctx);
        if (h->model != nullptr) llama_model_free(h->model);
        h->ctx = nullptr;
        h->model = nullptr;
    }
    delete h;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lianwenqi_app_ai_LocalEmbeddingEngine_nativeEmbeddingDim(
        JNIEnv * /*env*/, jobject /*this*/, jlong handle) {
    auto * h = reinterpret_cast<ModelHandle *>((intptr_t) handle);
    return (h != nullptr) ? (jint) h->n_embd : 0;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lianwenqi_app_ai_LocalEmbeddingEngine_nativeEmbed(
        JNIEnv * env, jobject /*this*/, jlong handle, jobjectArray jtexts) {
    auto * h = reinterpret_cast<ModelHandle *>((intptr_t) handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;

    const jsize count = env->GetArrayLength(jtexts);
    std::vector<std::vector<llama_token>> seqs;
    seqs.reserve((size_t) count);
    for (jsize i = 0; i < count; ++i) {
        jstring js = (jstring) env->GetObjectArrayElement(jtexts, i);
        const char * cs = (js != nullptr) ? env->GetStringUTFChars(js, nullptr) : nullptr;
        std::string text = (cs != nullptr) ? cs : " ";
        if (cs != nullptr) env->ReleaseStringUTFChars(js, cs);
        if (js != nullptr) env->DeleteLocalRef(js);
        if (text.empty()) text = " ";
        seqs.push_back(tokenize_seq(h, text));
    }

    std::vector<float> out;
    bool ok;
    {
        std::lock_guard<std::mutex> lock(h->mtx);
        ok = run_sequences(h, seqs, out);
    }
    if (!ok) return nullptr;

    jclass floatArrayClass = env->FindClass("[F");
    if (floatArrayClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(count, floatArrayClass, nullptr);
    for (jsize i = 0; i < count; ++i) {
        jfloatArray row = env->NewFloatArray(h->n_embd);
        env->SetFloatArrayRegion(row, 0, h->n_embd, out.data() + (size_t) i * (size_t) h->n_embd);
        env->SetObjectArrayElement(result, i, row);
        env->DeleteLocalRef(row);
    }
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_lianwenqi_app_ai_LocalEmbeddingEngine_nativeRerank(
        JNIEnv * env, jobject /*this*/, jlong handle, jstring jquery, jobjectArray jdocs) {
    auto * h = reinterpret_cast<ModelHandle *>((intptr_t) handle);
    if (h == nullptr || h->ctx == nullptr) return nullptr;

    auto read_utf8 = [env](jstring js) -> std::string {
        if (js == nullptr) return "";
        const char * cs = env->GetStringUTFChars(js, nullptr);
        std::string s = (cs != nullptr) ? cs : "";
        env->ReleaseStringUTFChars(js, cs);
        return s;
    };
    const std::string query = read_utf8(jquery);
    const jsize count = env->GetArrayLength(jdocs);

    // 模型自带 rerank 模板时按 {query}/{document} 拼；否则退回 SEP/EOS 拼接
    const std::string added_sep = llama_vocab_get_add_sep(h->vocab)
            ? llama_vocab_get_text(h->vocab, llama_vocab_sep(h->vocab)) : "";
    const std::string added_eos = llama_vocab_get_add_eos(h->vocab)
            ? llama_vocab_get_text(h->vocab, llama_vocab_eos(h->vocab)) : "";

    std::vector<std::vector<llama_token>> seqs;
    seqs.reserve((size_t) count);
    for (jsize i = 0; i < count; ++i) {
        jstring jd = (jstring) env->GetObjectArrayElement(jdocs, i);
        const std::string doc = read_utf8(jd);
        if (jd != nullptr) env->DeleteLocalRef(jd);

        std::string prompt;
        if (!h->rerank_template.empty()) {
            prompt = h->rerank_template;
            replace_all(prompt, "{query}", query);
            replace_all(prompt, "{document}", doc);
        } else {
            prompt = query + added_eos + added_sep + doc;
        }
        if (prompt.empty()) prompt = " ";
        seqs.push_back(tokenize_seq(h, prompt));
    }

    std::vector<float> out;
    bool ok;
    {
        std::lock_guard<std::mutex> lock(h->mtx);
        ok = run_sequences(h, seqs, out);
    }
    if (!ok) return nullptr;

    jfloatArray result = env->NewFloatArray(count);
    for (jsize i = 0; i < count; ++i) {
        const float score = out[(size_t) i * (size_t) h->n_embd];   // rerank 只有 1 个分类输出
        env->SetFloatArrayRegion(result, i, 1, &score);
    }
    return result;
}
