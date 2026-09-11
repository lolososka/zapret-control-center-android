#include <jni.h>

extern void SetPoolSize(int size);
extern void SetCfProxyCacheDir(const char *cache_dir);
extern void SetCfProxyConfig(int enabled, int priority, const char *user_domain);
extern int StartProxy(const char *host, int port, const char *dc_ips, const char *secret, int verbose);
extern int StopProxy(void);

static const char *utf(JNIEnv *env, jstring value) {
    return value == NULL ? "" : (*env)->GetStringUTFChars(env, value, NULL);
}

static void release_utf(JNIEnv *env, jstring value, const char *text) {
    if (value != NULL && text != NULL) (*env)->ReleaseStringUTFChars(env, value, text);
}

JNIEXPORT void JNICALL
Java_io_github_dovecoteescapee_byedpi_core_TelegramWsProxy_nativeConfigure(
        JNIEnv *env, jobject self, jint pool_size, jstring cache_dir,
        jboolean cloudflare, jstring domain) {
    (void) self;
    const char *cache = utf(env, cache_dir);
    const char *user_domain = utf(env, domain);
    SetPoolSize((int) pool_size);
    SetCfProxyCacheDir(cache);
    SetCfProxyConfig(cloudflare ? 1 : 0, 1, user_domain);
    release_utf(env, cache_dir, cache);
    release_utf(env, domain, user_domain);
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_TelegramWsProxy_nativeStart(
        JNIEnv *env, jobject self, jstring host, jint port,
        jstring dc_ips, jstring secret) {
    (void) self;
    const char *host_text = utf(env, host);
    const char *ips_text = utf(env, dc_ips);
    const char *secret_text = utf(env, secret);
    int result = StartProxy(host_text, (int) port, ips_text, secret_text, 1);
    release_utf(env, host, host_text);
    release_utf(env, dc_ips, ips_text);
    release_utf(env, secret, secret_text);
    return result;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_TelegramWsProxy_nativeStop(
        JNIEnv *env, jobject self) {
    (void) env;
    (void) self;
    return StopProxy();
}
