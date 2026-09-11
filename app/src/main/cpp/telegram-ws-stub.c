#include <stdint.h>

/*
 * The upstream Rust core currently ships ARM Android binaries only. These
 * symbols keep the JNI bridge loadable on x86 test/emulator builds; callers
 * receive a clear failure instead of a linker/install error.
 */
void SetPoolSize(int size) { (void) size; }
void SetCfProxyCacheDir(const char *cache_dir) { (void) cache_dir; }
void SetCfProxyConfig(int enabled, int priority, const char *user_domain) {
    (void) enabled;
    (void) priority;
    (void) user_domain;
}
int StartProxy(const char *host, int port, const char *dc_ips,
              const char *secret, int verbose) {
    (void) host;
    (void) port;
    (void) dc_ips;
    (void) secret;
    (void) verbose;
    return -1;
}
int StopProxy(void) { return 0; }
