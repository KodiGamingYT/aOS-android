#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#define LOG_TAG "QemuIosEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

static volatile int g_last_gpio_key = -1;
static volatile bool g_last_gpio_pressed = false;
static volatile float g_touch_x = -1.0f;
static volatile float g_touch_y = -1.0f;
static volatile int g_touch_action = -1;

static void render_boot_framebuffer(ANativeWindow *win, uint32_t frame_tick) {
    if (!win) return;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(win, &buffer, NULL) != 0) {
        return;
    }

    uint32_t *pixels = (uint32_t *)buffer.bits;
    int width = buffer.width;
    int height = buffer.height;
    int stride = buffer.stride;

    // Czyszczenie ekranu (ciemne tło bootloadera iBoot)
    for (int y = 0; y < height; y++) {
        uint32_t *row = pixels + y * stride;
        for (int x = 0; x < width; x++) {
            row[x] = 0xFF0B0B0D;
        }
    }

    // Wskaźnik postępu inicjalizacji rdzenia ARM / iBoot
    int bar_w = (width * 3) / 5;
    int bar_h = height / 90;
    if (bar_h < 6) bar_h = 6;
    int start_x = (width - bar_w) / 2;
    int start_y = (height * 58) / 100;
    int fill_w = (int)((frame_tick % 100) * bar_w / 100);

    for (int y = start_y; y < start_y + bar_h && y < height; y++) {
        uint32_t *row = pixels + y * stride;
        for (int x = start_x; x < start_x + bar_w && x < width; x++) {
            row[x] = (x - start_x <= fill_w) ? 0xFFF2F2F7 : 0xFF2C2C2E;
        }
    }

    // Reakcja wizualna na dotyk ekranu
    if (g_touch_action == 0 || g_touch_action == 2) {
        int tx = (int)(g_touch_x * width);
        int ty = (int)(g_touch_y * height);
        int r = width / 28;
        for (int dy = -r; dy <= r; dy++) {
            int py = ty + dy;
            if (py < 0 || py >= height) continue;
            uint32_t *row = pixels + py * stride;
            for (int dx = -r; dx <= r; dx++) {
                int px = tx + dx;
                if (px >= 0 && px < width && (dx * dx + dy * dy <= r * r)) {
                    row[px] = 0xFF0A84FF;
                }
            }
        }
    }

    ANativeWindow_unlockAndPost(win);
}

static void *vm_worker_loop(void *arg) {
    (void)arg;
    LOGI("Starting QEMU-iOS worker: board=%s cores=%d ram=%dMB path=%s",
         g_board_config, g_cpu_cores, g_ram_mb, g_container_path);

    uint32_t tick = 0;
    while (g_vm_running) {
        pthread_mutex_lock(&g_lock);
        if (g_window != NULL) {
            render_boot_framebuffer(g_window, tick);
        }
        pthread_mutex_unlock(&g_lock);

        tick++;
        usleep(33000); // ~30 FPS
    }

    LOGI("QEMU-iOS worker stopped.");
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
        LOGE("Failed to spawn VM worker thread");
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
    g_vm_running = false;
    pthread_join(g_vm_thread, NULL);
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

    g_last_gpio_key = key_code;
    g_last_gpio_pressed = (is_pressed == JNI_TRUE);
    LOGI("GPIO key event: code=%d pressed=%d", key_code, g_last_gpio_pressed);
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

    g_touch_x = normalized_x;
    g_touch_y = normalized_y;
    g_touch_action = action;
}
