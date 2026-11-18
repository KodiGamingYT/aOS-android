#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "QemuIosEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Deklaracje punktów wejścia QEMU system emulator */
extern int qemu_init(int argc, char **argv, char **envp);
extern void qemu_main_loop(void);
extern void qemu_cleanup(void);
extern void qemu_system_shutdown_request(int reason);

static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_vm_thread;
static volatile bool g_vm_running = false;

static char g_container_path[512];
static char g_board_config[64];
static int g_cpu_cores = 4;
static int g_ram_mb = 1024;
static float g_res_scale = 0.75f;
static int g_renderer_id = 0;
static bool g_net_enabled = true;

/* Bufor ramki wideo współdzielony między framebufferem QEMU a Android Surface */
static uint32_t *g_fb_pixels = NULL;
static int g_fb_width = 640;
static int g_fb_height = 1136;

/* Funkcja wywoływana przez sterownik LCD/framebuffer w qemu-ios przy odświeżaniu klatki */
void aos_push_framebuffer(const uint32_t *src_bgra, int width, int height) {
    pthread_mutex_lock(&g_lock);
    if (!g_window || !src_bgra || width <= 0 || height <= 0) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(g_window, &buffer, NULL) != 0) {
        pthread_mutex_unlock(&g_lock);
        return;
    }

    uint32_t *dst = (uint32_t *)buffer.bits;
    int dst_w = buffer.width;
    int dst_h = buffer.height;
    int stride = buffer.stride;

    for (int y = 0; y < dst_h; y++) {
        int sy = (y * height) / dst_h;
        if (sy >= height) sy = height - 1;
        const uint32_t *src_row = src_bgra + sy * width;
        uint32_t *dst_row = dst + y * stride;

        for (int x = 0; x < dst_w; x++) {
            int sx = (x * width) / dst_w;
            if (sx >= width) sx = width - 1;
            uint32_t p = src_row[sx];
            /* Konwersja BGRA -> RGBA dla ANativeWindow (WINDOW_FORMAT_RGBA_8888) */
            uint8_t b = (p >> 0) & 0xFF;
            uint8_t g = (p >> 8) & 0xFF;
            uint8_t r = (p >> 16) & 0xFF;
            uint8_t a = 0xFF;
            dst_row[x] = ((uint32_t)a << 24) | ((uint32_t)b << 16) | ((uint32_t)g << 8) | (uint32_t)r;
        }
    }

    ANativeWindow_unlockAndPost(g_window);
    pthread_mutex_unlock(&g_lock);
}

static const char *resolve_machine_type(const char *board) {
    if (strcmp(board, "n90ap") == 0) {
        return "s5l8930x"; /* iPhone 4 (Apple A4) */
    }
    return "s5l8950x";     /* iPhone 5 n41ap / n42ap (Apple A6) */
}

static void *vm_worker_loop(void *arg) {
    (void)arg;

    char kernel_path[600];
    char dtb_path[600];
    char rootfs_path[600];
    char ram_arg[32];
    char smp_arg[32];
    char drive_arg[700];

    snprintf(kernel_path, sizeof(kernel_path), "%s/kernelcache", g_container_path);
    snprintf(dtb_path, sizeof(dtb_path), "%s/devicetree.img3", g_container_path);
    snprintf(rootfs_path, sizeof(rootfs_path), "%s/rootfs.dmg", g_container_path);
    snprintf(ram_arg, sizeof(ram_arg), "%dM", g_ram_mb);
    snprintf(smp_arg, sizeof(smp_arg), "%d", g_cpu_cores);
    snprintf(drive_arg, sizeof(drive_arg), "file=%s,format=raw,if=none,id=nand0", rootfs_path);

    const char *machine = resolve_machine_type(g_board_config);

    LOGI("Booting QEMU-iOS machine=%s board=%s ram=%s smp=%s kernel=%s",
         machine, g_board_config, ram_arg, smp_arg, kernel_path);

    char *argv[] = {
        "qemu-system-arm",
        "-M", (char *)machine,
        "-m", ram_arg,
        "-smp", smp_arg,
        "-kernel", kernel_path,
        "-dtb", dtb_path,
        "-drive", drive_arg,
        "-serial", "stdio",
        "-display", "none",
        NULL
    };
    int argc = 15;

    qemu_init(argc, argv, NULL);
    qemu_main_loop();
    qemu_cleanup();

    g_vm_running = false;
    LOGI("QEMU-iOS main loop exited.");
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeStartVm(
        JNIEnv *env,
        jobject thiz,
        jstring container_path,
        jstring board_config,
        jint cpu_cores,
        jint ram_mb,
        jfloat resolution_scale,
        jint renderer_id,
        jboolean network_enabled) {
    (void)thiz;

    pthread_mutex_lock(&g_lock);
    if (g_vm_running) {
        pthread_mutex_unlock(&g_lock);
        return JNI_TRUE;
    }

    const char *c_path = (*env)->GetStringUTFChars(env, container_path, NULL);
    const char *c_board = (*env)->GetStringUTFChars(env, board_config, NULL);

    strncpy(g_container_path, c_path ? c_path : "", sizeof(g_container_path) - 1);
    g_container_path[sizeof(g_container_path) - 1] = '\0';

    strncpy(g_board_config, c_board ? c_board : "n90ap", sizeof(g_board_config) - 1);
    g_board_config[sizeof(g_board_config) - 1] = '\0';

    if (c_path) (*env)->ReleaseStringUTFChars(env, container_path, c_path);
    if (c_board) (*env)->ReleaseStringUTFChars(env, board_config, c_board);

    g_cpu_cores = cpu_cores;
    g_ram_mb = ram_mb;
    g_res_scale = resolution_scale;
    g_renderer_id = renderer_id;
    g_net_enabled = (network_enabled == JNI_TRUE);
    g_vm_running = true;
    pthread_mutex_unlock(&g_lock);

    if (pthread_create(&g_vm_thread, NULL, vm_worker_loop, NULL) != 0) {
        LOGE("Failed to create QEMU-iOS worker thread");
        g_vm_running = false;
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeStopVm(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    if (!g_vm_running) return;
    qemu_system_shutdown_request(1);
    pthread_join(g_vm_thread, NULL);
    g_vm_running = false;
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeAttachSurface(
        JNIEnv *env,
        jobject thiz,
        jobject surface) {
    (void)thiz;

    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    if (surface != NULL) {
        g_window = ANativeWindow_fromSurface(env, surface);
        if (g_window != NULL) {
            ANativeWindow_setBuffersGeometry(g_window, 0, 0, WINDOW_FORMAT_RGBA_8888);
        }
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeDetachSurface(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_lock);
    if (g_window != NULL) {
        ANativeWindow_release(g_window);
        g_window = NULL;
    }
    pthread_mutex_unlock(&g_lock);
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeSendGpioKey(
        JNIEnv *env,
        jobject thiz,
        jint key_code,
        jboolean is_pressed) {
    (void)env;
    (void)thiz;
    LOGI("QEMU GPIO key=%d pressed=%d", key_code, is_pressed == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeSendTouchEvent(
        JNIEnv *env,
        jobject thiz,
        jfloat normalized_x,
        jfloat normalized_y,
        jint action) {
    (void)env;
    (void)thiz;
    (void)normalized_x;
    (void)normalized_y;
    (void)action;
}
