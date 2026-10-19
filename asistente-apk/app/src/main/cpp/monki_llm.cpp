// Puente JNI mínimo a llama.cpp: cargar un modelo GGUF, responder una conversación y liberar.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "MonkiLLM", __VA_ARGS__)

namespace {
struct Engine {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    std::string tmpl;
};
std::atomic<bool> g_stop{false};

std::string jstr(JNIEnv * env, jstring s) {
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Quita bytes UTF-8 incompletos al final (un token puede cortar una letra con acento)
size_t valid_utf8_prefix(const std::string & s) {
    size_t i = s.size();
    size_t back = 0;
    while (i > 0 && back < 4) {
        unsigned char c = s[i - 1];
        if ((c & 0xC0) != 0x80) {
            size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : 4;
            return (back + 1 >= need) ? s.size() : i - 1;
        }
        i--; back++;
    }
    return s.size();
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_limber_asistente_LocalLlm_nativeLoad(JNIEnv * env, jobject, jstring path, jint nCtx, jint threads) {
    llama_backend_init();
    auto mp = llama_model_default_params();
    llama_model * model = llama_model_load_from_file(jstr(env, path).c_str(), mp);
    if (!model) { LOG("no pude cargar el modelo"); return 0; }
    auto cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); LOG("no pude crear el contexto"); return 0; }
    auto * e = new Engine{model, ctx, ""};
    if (const char * t = llama_model_chat_template(model, nullptr)) e->tmpl = t;
    LOG("modelo listo (ctx %d, %d hilos)", nCtx, threads);
    return reinterpret_cast<jlong>(e);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limber_asistente_LocalLlm_nativeChat(JNIEnv * env, jobject, jlong ptr, jobjectArray roles, jobjectArray contents, jint maxTokens) {
    auto * e = reinterpret_cast<Engine *>(ptr);
    if (!e) return env->NewStringUTF("");
    g_stop = false;

    // Conversación → texto con la plantilla de chat del modelo
    jsize n = env->GetArrayLength(roles);
    std::vector<std::string> r(n), c(n);
    std::vector<llama_chat_message> msgs(n);
    for (jsize i = 0; i < n; i++) {
        r[i] = jstr(env, (jstring) env->GetObjectArrayElement(roles, i));
        c[i] = jstr(env, (jstring) env->GetObjectArrayElement(contents, i));
        msgs[i] = {r[i].c_str(), c[i].c_str()};
    }
    const char * tmpl = e->tmpl.empty() ? nullptr : e->tmpl.c_str();
    std::vector<char> buf(8192);
    int len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), (int) buf.size());
    if (len > (int) buf.size()) { buf.resize(len); len = llama_chat_apply_template(tmpl, msgs.data(), n, true, buf.data(), len); }
    if (len < 0) return env->NewStringUTF("");
    std::string prompt(buf.data(), len);

    const llama_vocab * vocab = llama_model_get_vocab(e->model);
    llama_memory_clear(llama_get_memory(e->ctx), true);
    int nTok = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, true, true);
    std::vector<llama_token> toks(nTok);
    llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), nTok, true, true);
    if (nTok >= (int) llama_n_ctx(e->ctx) - 8) toks.erase(toks.begin(), toks.end() - (llama_n_ctx(e->ctx) - 64));

    // Prompt en trozos del tamaño del lote
    for (size_t i = 0; i < toks.size(); i += 512) {
        int k = (int) std::min<size_t>(512, toks.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(toks.data() + i, k)) != 0) return env->NewStringUTF("");
        if (g_stop) return env->NewStringUTF("");
    }

    auto * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // top-k antes de las penalizaciones: sobre todo el vocabulario serían lentas
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.6f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    for (int i = 0; i < maxTokens && !g_stop; i++) {
        llama_token t = llama_sampler_sample(smpl, e->ctx, -1);
        if (llama_vocab_is_eog(vocab, t)) break;
        char piece[256];
        int m = llama_token_to_piece(vocab, t, piece, sizeof(piece), 0, false);
        if (m > 0) out.append(piece, m);
        if (llama_decode(e->ctx, llama_batch_get_one(&t, 1)) != 0) break;
    }
    llama_sampler_free(smpl);
    out.resize(valid_utf8_prefix(out));
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_limber_asistente_LocalLlm_nativeStop(JNIEnv *, jobject) { g_stop = true; }

extern "C" JNIEXPORT void JNICALL
Java_com_limber_asistente_LocalLlm_nativeFree(JNIEnv *, jobject, jlong ptr) {
    auto * e = reinterpret_cast<Engine *>(ptr);
    if (!e) return;
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}
