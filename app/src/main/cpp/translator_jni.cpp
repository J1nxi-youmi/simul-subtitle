// Minimal llama.cpp JNI wrapper: one model + one context, greedy decode, abortable.
#include <jni.h>
#include <atomic>
#include <string>
#include <vector>
#include "llama.h"


struct Translator {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    std::atomic<bool> cancel{false}; // sticky; set once when the session stops
};

extern "C" JNIEXPORT jlong JNICALL
Java_dev_krita_jpsub_LlamaTranslator_nativeLoad(JNIEnv *env, jclass, jstring jpath, jint nThreads) {
    llama_backend_init();
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(jpath, path);
    if (!model) return 0;

    auto cp = llama_context_default_params();
    cp.n_ctx = 1024;
    cp.n_batch = 512;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); return 0; }

    auto *t = new Translator();
    t->model = model;
    t->ctx = ctx;
    t->vocab = llama_model_get_vocab(model);
    llama_set_abort_callback(ctx, [](void *d) { return static_cast<Translator *>(d)->cancel.load(); }, t);
    t->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(t->smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(t->vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(t->smpl, llama_sampler_init_greedy());
    return reinterpret_cast<jlong>(t);
}

// Returns UTF-8 bytes of the translation, or null when cancelled / failed.
extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_krita_jpsub_LlamaTranslator_nativeTranslate(JNIEnv *env, jclass, jlong h, jstring jprompt, jint maxTokens) {
    auto *t = reinterpret_cast<Translator *>(h);
    // cancel is sticky: it is only used to stop the session, so it is never reset.
    if (t->cancel.load()) return nullptr;
    const char *p = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jprompt, p);

    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(t->vocab, prompt.c_str(), (int) prompt.size(), toks.data(), (int) toks.size(),
                           /*add_special*/ false, /*parse_special*/ true);
    if (n < 0) return nullptr;
    toks.resize(n);

    llama_memory_clear(llama_get_memory(t->ctx), true);
    llama_sampler_reset(t->smpl);
    // abort_callback makes this return non-zero mid-prefill when cancelled
    if (llama_decode(t->ctx, llama_batch_get_one(toks.data(), n)) != 0) return nullptr;

    std::string out;
    char buf[256];
    for (int i = 0; i < maxTokens; i++) {
        if (t->cancel.load()) return nullptr;
        llama_token id = llama_sampler_sample(t->smpl, t->ctx, -1);
        if (llama_vocab_is_eog(t->vocab, id)) break;
        int len = llama_token_to_piece(t->vocab, id, buf, sizeof(buf), 0, false);
        if (len > 0) out.append(buf, len);
        if (llama_decode(t->ctx, llama_batch_get_one(&id, 1)) != 0) return nullptr;
    }
    jbyteArray arr = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(arr, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_krita_jpsub_LlamaTranslator_nativeCancel(JNIEnv *, jclass, jlong h) {
    if (h) reinterpret_cast<Translator *>(h)->cancel.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_krita_jpsub_LlamaTranslator_nativeFree(JNIEnv *, jclass, jlong h) {
    auto *t = reinterpret_cast<Translator *>(h);
    if (!t) return;
    llama_sampler_free(t->smpl);
    llama_free(t->ctx);
    llama_model_free(t->model);
    delete t;
}
