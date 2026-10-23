// JNI entry for byedpi, adapted from romanvht/ByeByeDPI (GPL-3.0). byedpi keeps its state in globals, so the
// proxy runs once per process: the :dpi processes are killed to stop it or to change the strategy.
#include <dlfcn.h>
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

#include "byedpi/error.h"

int main(int argc, char **argv);

static pthread_mutex_t proxy_mutex = PTHREAD_MUTEX_INITIALIZER;
static int proxy_started;

// Linked in place of daemon(3) with --wrap: forking from an app process is never wanted.
int __wrap_daemon(__attribute__((unused)) int nochdir, __attribute__((unused)) int noclose) {
    errno = EPERM;
    return -1;
}

// Blocks while the proxy serves; returns byedpi's exit code, or -1 if this process already ran it.
JNIEXPORT jint JNICALL
Java_dev_wahha_wtube_dpi_ByeDpi_run(JNIEnv *env, __attribute__((unused)) jobject thiz, jobjectArray args) {
    pthread_mutex_lock(&proxy_mutex);
    int used = proxy_started;
    proxy_started = 1;
    pthread_mutex_unlock(&proxy_mutex);
    if (used) {
        LOG(LOG_E, "proxy already ran in this process");
        return -1;
    }

    int argc = (*env)->GetArrayLength(env, args);
    char **argv = calloc(argc + 1, sizeof(char *));
    int result = -1;
    if (!argv) {
        return result;
    }
    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring) (*env)->GetObjectArrayElement(env, args, i);
        if (!arg) {
            goto cleanup;
        }
        const char *chars = (*env)->GetStringUTFChars(env, arg, NULL);
        if (chars) {
            argv[i] = strdup(chars);
            (*env)->ReleaseStringUTFChars(env, arg, chars);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) {
            goto cleanup;
        }
    }

    // byedpi logs every socket event at debug/verbose: thousands of lines a minute while a video plays.
    // __android_log_set_minimum_priority exists from Android 11; older phones keep the noise.
    int32_t (*set_minimum)(int32_t) = (int32_t (*)(int32_t)) dlsym(RTLD_DEFAULT, "__android_log_set_minimum_priority");
    if (set_minimum) set_minimum(ANDROID_LOG_INFO);
    LOG(LOG_E, "starting proxy with %d args", argc);
    result = main(argc, argv);
    LOG(LOG_E, "proxy returned %d", result);

cleanup:
    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    return result;
}
