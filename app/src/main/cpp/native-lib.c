#include <string.h>
#include <netdb.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <sys/socket.h>
#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include "byedpi/error.h"
#include "byedpi/proxy.h"
#include "byedpi/params.h"
#include "byedpi/packets.h"
#include "main.h"
#include "utils.h"

const enum demode DESYNC_METHODS[] = {
    DESYNC_NONE,
    DESYNC_SPLIT,
    DESYNC_DISORDER,
    DESYNC_FAKE,
    DESYNC_OOB,
    DESYNC_DISOOB,
};

enum hosts_mode {
    HOSTS_DISABLE,
    HOSTS_BLACKLIST,
    HOSTS_WHITELIST,
};

// ByeDPI has process-global parameters. Serialize preparation and teardown across
// both Android services, but never hold this mutex while the event loop runs.
static pthread_mutex_t session_mutex = PTHREAD_MUTEX_INITIALIZER;
static int session_handle = -1;
static int next_handle = 0;
static int session_fd = -1;
static int control_fd = -1;
static int session_running = 0;
static char **session_args = NULL;
static int session_argc = 0;

static void release_params(void) {
    reset_params();
    // Some command-line options retain pointers into argv until the loop exits.
    for (int i = 0; i < session_argc; i++) {
        free(session_args[i]);
    }
    free(session_args);
    session_args = NULL;
    session_argc = 0;
}

static int begin_create(void) {
    pthread_mutex_lock(&session_mutex);
    if (session_handle >= 0 || next_handle == INT_MAX) {
        pthread_mutex_unlock(&session_mutex);
        return 0;
    }
    return 1;
}

// Called with session_mutex held on every preparation exit, including failure.
static int finish_create(int fd) {
    int handle = -1;
    if (fd >= 0) {
        // Keep a duplicate owned by the control path: event_loop closes its own
        // fd before it returns, so using that integer to stop could hit another
        // socket that happened to reuse it in the meantime.
        control_fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        if (control_fd >= 0) {
            session_fd = fd;
            session_handle = handle = ++next_handle;
        } else {
            close(fd);
        }
    }
    if (handle < 0) {
        release_params();
    }
    pthread_mutex_unlock(&session_mutex);
    return handle;
}

JNIEXPORT jint JNI_OnLoad(
        __attribute__((unused)) JavaVM *vm,
        __attribute__((unused)) void *reserved) {
    default_params = params;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniCreateSocketWithCommandLine(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jobjectArray args) {
    if (!begin_create()) return -1;
    int argc = (*env)->GetArrayLength(env, args);
    if (argc < 1) return finish_create(-1);
    session_args = calloc((size_t)argc + 1, sizeof(char *));
    if (!session_args) return finish_create(-1);
    session_argc = argc;
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) return finish_create(-1);
        const char *arg_str = (*env)->GetStringUTFChars(env, arg, 0);
        if (!arg_str) {
            (*env)->DeleteLocalRef(env, arg);
            return finish_create(-1);
        }
        session_args[i] = strdup(arg_str);
        (*env)->ReleaseStringUTFChars(env, arg, arg_str);
        (*env)->DeleteLocalRef(env, arg);
        if (!session_args[i]) return finish_create(-1);
    }

    int res = parse_args(argc, session_args);
    if (res < 0) {
        uniperror("parse_args");
        return finish_create(-1);
    }

    int fd = listen_socket((struct sockaddr_ina *)&params.laddr);
    if (fd < 0) {
        uniperror("listen_socket");
        return finish_create(-1);
    }
    LOG(LOG_S, "listen_socket, fd: %d", fd);

    return finish_create(fd);
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniCreateSocket(
        JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jstring ip,
        jint port,
        jint max_connections,
        jint buffer_size,
        jint default_ttl,
        jboolean custom_ttl,
        jboolean no_domain,
        jboolean desync_http,
        jboolean desync_https,
        jboolean desync_udp,
        jint desync_method,
        jint split_position,
        jboolean split_at_host,
        jint fake_ttl,
        jstring fake_sni,
        jbyte custom_oob_char,
        jboolean host_mixed_case,
        jboolean domain_mixed_case,
        jboolean host_remove_spaces,
        jboolean tls_record_split,
        jint tls_record_split_position,
        jboolean tls_record_split_at_sni,
        jint hosts_mode,
        jstring hosts,
        jboolean tfo,
        jint udp_fake_count,
        jboolean drop_sack,
        jint fake_offset) {
    if (!begin_create()) return -1;
    struct sockaddr_ina s;

    const char *address = (*env)->GetStringUTFChars(env, ip, 0);
    int res = get_addr(address, &s);
    (*env)->ReleaseStringUTFChars(env, ip, address);
    if (res < 0) {
        uniperror("get_addr");
        return finish_create(-1);
    }

    s.in.sin_port = htons(port);

    params.max_open = max_connections;
    params.bfsize = buffer_size;
    params.resolve = !no_domain;
    params.tfo = tfo;

    if (custom_ttl) {
        params.def_ttl = default_ttl;
        params.custom_ttl = 1;
    }

    if (!params.def_ttl) {
        if ((params.def_ttl = get_default_ttl()) < 1) {
            uniperror("get_default_ttl");
            return finish_create(-1);
        }
    }

    if (hosts_mode == HOSTS_WHITELIST) {
        struct desync_params *dp = add(
                (void *) &params.dp,
                &params.dp_count,
                sizeof(struct desync_params)
        );
        if (!dp) {
            uniperror("add");
            return finish_create(-1);
        }

        const char *str = (*env)->GetStringUTFChars(env, hosts, 0);
        dp->file_ptr = data_from_str(str, &dp->file_size);
        (*env)->ReleaseStringUTFChars(env, hosts, str);
        dp->hosts = parse_hosts(dp->file_ptr, dp->file_size);
        if (!dp->hosts) {
            perror("parse_hosts");
            return finish_create(-1);
        }
    }

    struct desync_params *dp = add(
            (void *) &params.dp,
            &params.dp_count,
            sizeof(struct desync_params)
    );
    if (!dp) {
        uniperror("add");
        return finish_create(-1);
    }

    if (hosts_mode == HOSTS_BLACKLIST) {
        const char *str = (*env)->GetStringUTFChars(env, hosts, 0);
        dp->file_ptr = data_from_str(str, &dp->file_size);
        (*env)->ReleaseStringUTFChars(env, hosts, str);
        dp->hosts = parse_hosts(dp->file_ptr, dp->file_size);
        if (!dp->hosts) {
            perror("parse_hosts");
            return finish_create(-1);
        }
    }

    dp->ttl = fake_ttl;
    dp->udp_fake_count = udp_fake_count;
    dp->drop_sack = drop_sack;
    dp->proto =
            IS_HTTP * desync_http |
            IS_HTTPS * desync_https |
            IS_UDP * desync_udp;
    dp->mod_http =
            MH_HMIX * host_mixed_case |
            MH_DMIX * domain_mixed_case |
            MH_SPACE * host_remove_spaces;

    struct part *part = add(
            (void *) &dp->parts,
            &dp->parts_n,
            sizeof(struct part)
    );
    if (!part) {
        uniperror("add");
        return finish_create(-1);
    }

    enum demode mode = DESYNC_METHODS[desync_method];

    int offset_flag = dp->proto || desync_https ? OFFSET_SNI : OFFSET_HOST;

    part->flag = split_at_host ? offset_flag : 0;
    part->pos = split_position;
    part->m = mode;

    if (tls_record_split) {
        struct part *tlsrec_part = add(
                (void *) &dp->tlsrec,
                &dp->tlsrec_n,
                sizeof(struct part)
        );

        if (!tlsrec_part) {
            uniperror("add");
            return finish_create(-1);
        }

        tlsrec_part->flag = tls_record_split_at_sni ? offset_flag : 0;
        tlsrec_part->pos = tls_record_split_position;
    }

    if (mode == DESYNC_FAKE) {
        dp->fake_offset = fake_offset;

        const char *sni = (*env)->GetStringUTFChars(env, fake_sni, 0);
        LOG(LOG_S, "fake_sni: %s", sni);
        res = change_tls_sni(sni, fake_tls.data, fake_tls.size);
        (*env)->ReleaseStringUTFChars(env, fake_sni, sni);
        if (res) {
            fprintf(stderr, "error chsni\n");
            return finish_create(-1);
        }
    }

    if (mode == DESYNC_OOB) {
        dp->oob_char[0] = custom_oob_char;
        dp->oob_char[1] = 1;
    }

    if (dp->proto) {
        dp = add((void *)&params.dp,
                 &params.dp_count, sizeof(struct desync_params));
        if (!dp) {
            uniperror("add");
            return finish_create(-1);
        }
    }

    params.mempool = mem_pool(0);
    if (!params.mempool) {
        uniperror("mem_pool");
        return finish_create(-1);
    }

    int fd = listen_socket(&s);
    if (fd < 0) {
        uniperror("listen_socket");
        return finish_create(-1);
    }
    LOG(LOG_S, "listen_socket, fd: %d", fd);

    return finish_create(fd);
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniStartProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint handle) {
    pthread_mutex_lock(&session_mutex);
    if (handle != session_handle || session_running) {
        pthread_mutex_unlock(&session_mutex);
        return ECANCELED;
    }
    int fd = session_fd;
    session_running = 1;
    LOG(LOG_S, "start_proxy, fd: %d", fd);
    NOT_EXIT = 1;
    pthread_mutex_unlock(&session_mutex);
    int res = event_loop(fd);
    int error = res < 0 ? get_e() : 0;
    if (res < 0) {
        uniperror("event_loop");
    }

    // Params are shared with event_loop and its connection handlers. Free them
    // only after that loop has fully returned; the stop JNI call runs on a
    // different thread and must never invalidate memory still in use here.
    pthread_mutex_lock(&session_mutex);
    close(control_fd);
    control_fd = session_fd = session_handle = -1;
    session_running = 0;
    release_params();
    pthread_mutex_unlock(&session_mutex);
    return res < 0 ? error : 0;
}

JNIEXPORT jint JNICALL
Java_io_github_dovecoteescapee_byedpi_core_ByeDpiProxy_jniStopProxy(
        __attribute__((unused)) JNIEnv *env,
        __attribute__((unused)) jobject thiz,
        jint handle) {
    pthread_mutex_lock(&session_mutex);
    if (handle != session_handle) {
        pthread_mutex_unlock(&session_mutex);
        return 0; // Already finished; never act on a later session.
    }
    int error = 0;
    if (session_running) {
        LOG(LOG_S, "stop_proxy, control fd: %d", control_fd);
        if (shutdown(control_fd, SHUT_RDWR) < 0) error = get_e();
    } else {
        // Stop won the race with the worker. No event loop can access these
        // resources, and its captured generation will now be rejected by Start.
        close(session_fd);
        close(control_fd);
        control_fd = session_fd = session_handle = -1;
        release_params();
    }
    pthread_mutex_unlock(&session_mutex);
    return error;
}
