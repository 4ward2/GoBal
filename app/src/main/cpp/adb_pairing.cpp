/*
 * The cryptographic half of wireless-debugging pairing: SPAKE2 over the pairing code, then
 * AES-128-GCM for the two peer-info packets.
 *
 * Adapted from Shizuku (manager/src/main/jni/adb_pairing.cpp),
 * Copyright (c) RikkaApps, licensed under the Apache License, Version 2.0.
 * Changes: bound to this app's class, logging without the helper header, input copied instead
 * of pinned, variable-length arrays replaced.
 *
 * Built outside Gradle with scripts/build-adb-pairing.sh; the result is checked in under jniLibs.
 */
#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <android/log.h>
#include <openssl/aead.h>
#include <openssl/curve25519.h>
#include <openssl/evp.h>
#include <openssl/hkdf.h>
#include <openssl/mem.h>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "MCP:ShellPair", __VA_ARGS__)

static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";
static constexpr size_t kHkdfKeyLength = 16;

struct PairingContextNative {
    SPAKE2_CTX *spake2_ctx;
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t msg_size;
    EVP_AEAD_CTX *aes_ctx;
    uint64_t dec_sequence;
    uint64_t enc_sequence;
};

static std::vector<uint8_t> bytesOf(JNIEnv *env, jbyteArray array) {
    auto size = env->GetArrayLength(array);
    std::vector<uint8_t> out(static_cast<size_t>(size));
    if (size > 0) env->GetByteArrayRegion(array, 0, size, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

static jbyteArray arrayOf(JNIEnv *env, const uint8_t *data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    if (out != nullptr) env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte *>(data));
    return out;
}

static jlong Pairing_Create(JNIEnv *env, jclass, jbyteArray jPassword) {
    auto spake2_ctx = SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName));
    if (spake2_ctx == nullptr) {
        LOGE("no SPAKE2 context");
        return 0;
    }
    auto password = bytesOf(env, jPassword);
    auto ctx = static_cast<PairingContextNative *>(calloc(1, sizeof(PairingContextNative)));
    if (ctx == nullptr) {
        SPAKE2_CTX_free(spake2_ctx);
        return 0;
    }
    int ok = SPAKE2_generate_msg(spake2_ctx, ctx->msg, &ctx->msg_size, sizeof(ctx->msg), password.data(), password.size());
    OPENSSL_cleanse(password.data(), password.size());
    if (ok != 1 || ctx->msg_size == 0) {
        LOGE("no SPAKE2 message");
        SPAKE2_CTX_free(spake2_ctx);
        free(ctx);
        return 0;
    }
    ctx->spake2_ctx = spake2_ctx;
    return reinterpret_cast<jlong>(ctx);
}

static jbyteArray Pairing_Msg(JNIEnv *env, jclass, jlong ptr) {
    auto ctx = reinterpret_cast<PairingContextNative *>(ptr);
    return arrayOf(env, ctx->msg, ctx->msg_size);
}

static jboolean Pairing_InitCipher(JNIEnv *env, jclass, jlong ptr, jbyteArray jTheirMsg) {
    auto ctx = reinterpret_cast<PairingContextNative *>(ptr);
    auto their = bytesOf(env, jTheirMsg);
    if (their.size() > SPAKE2_MAX_MSG_SIZE || ctx->aes_ctx != nullptr) return JNI_FALSE;

    size_t material_size = 0;
    uint8_t material[SPAKE2_MAX_KEY_SIZE];
    if (SPAKE2_process_msg(ctx->spake2_ctx, material, &material_size, sizeof(material), their.data(), their.size()) != 1) {
        LOGE("their SPAKE2 message was not accepted");
        return JNI_FALSE;
    }
    uint8_t key[kHkdfKeyLength];
    const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    int ok = HKDF(key, sizeof(key), EVP_sha256(), material, material_size, nullptr, 0, info, sizeof(info) - 1);
    OPENSSL_cleanse(material, sizeof(material));
    if (ok != 1) return JNI_FALSE;
    ctx->aes_ctx = EVP_AEAD_CTX_new(EVP_aead_aes_128_gcm(), key, sizeof(key), EVP_AEAD_DEFAULT_TAG_LENGTH);
    OPENSSL_cleanse(key, sizeof(key));
    return ctx->aes_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray crypt(JNIEnv *env, PairingContextNative *ctx, jbyteArray jIn, bool seal) {
    if (ctx->aes_ctx == nullptr) return nullptr;
    auto in = bytesOf(env, jIn);
    auto aead = EVP_AEAD_CTX_aead(ctx->aes_ctx);
    std::vector<uint8_t> out(in.size() + EVP_AEAD_max_overhead(aead));
    std::vector<uint8_t> nonce(EVP_AEAD_nonce_length(aead), 0);
    uint64_t *sequence = seal ? &ctx->enc_sequence : &ctx->dec_sequence;
    memcpy(nonce.data(), sequence, sizeof(uint64_t));

    size_t written = 0;
    int ok = seal
        ? EVP_AEAD_CTX_seal(ctx->aes_ctx, out.data(), &written, out.size(), nonce.data(), nonce.size(), in.data(), in.size(), nullptr, 0)
        : EVP_AEAD_CTX_open(ctx->aes_ctx, out.data(), &written, out.size(), nonce.data(), nonce.size(), in.data(), in.size(), nullptr, 0);
    if (!ok) return nullptr;
    ++*sequence;
    return arrayOf(env, out.data(), written);
}

static jbyteArray Pairing_Encrypt(JNIEnv *env, jclass, jlong ptr, jbyteArray jIn) {
    return crypt(env, reinterpret_cast<PairingContextNative *>(ptr), jIn, true);
}

static jbyteArray Pairing_Decrypt(JNIEnv *env, jclass, jlong ptr, jbyteArray jIn) {
    return crypt(env, reinterpret_cast<PairingContextNative *>(ptr), jIn, false);
}

static void Pairing_Destroy(JNIEnv *, jclass, jlong ptr) {
    auto ctx = reinterpret_cast<PairingContextNative *>(ptr);
    if (ctx == nullptr) return;
    SPAKE2_CTX_free(ctx->spake2_ctx);
    if (ctx->aes_ctx != nullptr) EVP_AEAD_CTX_free(ctx->aes_ctx);
    free(ctx);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;

    JNINativeMethod methods[] = {
        {"create",     "([B)J",   reinterpret_cast<void *>(Pairing_Create)},
        {"message",    "(J)[B",   reinterpret_cast<void *>(Pairing_Msg)},
        {"initCipher", "(J[B)Z",  reinterpret_cast<void *>(Pairing_InitCipher)},
        {"encrypt",    "(J[B)[B", reinterpret_cast<void *>(Pairing_Encrypt)},
        {"decrypt",    "(J[B)[B", reinterpret_cast<void *>(Pairing_Decrypt)},
        {"destroy",    "(J)V",    reinterpret_cast<void *>(Pairing_Destroy)},
    };
    jclass clazz = env->FindClass("com/danielealbano/androidremotecontrolmcp/services/shell/PairingNative");
    if (clazz == nullptr) return -1;
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(JNINativeMethod)) != JNI_OK) return -1;
    return JNI_VERSION_1_6;
}
