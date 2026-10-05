#include <jni.h>
#include <android/log.h>
#include <string.h>

static void netease_log_policy_logger(const struct __android_log_message *message) {
    const char *tag = message->tag;
    const int is_security_tag = tag != NULL && (
            strncmp(tag, "Aegis", 5) == 0 ||
            strncmp(tag, "NetDev", 6) == 0 ||
            strstr(tag, "DeviceId") != NULL);
    if (is_security_tag && message->priority < ANDROID_LOG_ERROR) {
        return;
    }
    __android_log_logd_logger(message);
}

JNIEXPORT void JNICALL
Java_com_ljyh_mei_data_network_NeteaseNativeLogPolicy_installSecurityFilter(
        JNIEnv *env,
        jclass clazz) {
    (void) env;
    (void) clazz;
    __android_log_set_minimum_priority(ANDROID_LOG_DEFAULT);
    __android_log_set_logger(netease_log_policy_logger);
}
