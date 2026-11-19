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

extern int qemu_init(int argc, char **argv, char **envp);
extern void qemu_main_loop(void);
extern void qemu_cleanup(void);
extern void qemu_system_shutdown_request(int reason);

static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_vm_thread;
static volatile bool g_vm_running = false;
static bool g_stderr_hooked = false;

static char g_container_path[512];
static char g_board_config[64];
static int g_cpu_cores = 1;
static int g_ram_mb = 128;
static float g_res_scale = 0.75f;
static int g_renderer_id = 0;
static bool g_net_enabled = true;

static void *stderr_log_thread(void *arg) {
    int read_fd = *(int *)arg;
    free(arg);
    char buf[512];
    while (1) {
        ssize_t n = read(read_fd, buf, sizeof(buf) - 1);
        if (n <= 0) break;
        buf[n] = '\0';
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) {
            buf[--n] = '\0';
        }
        if (n > 0) {
            LOGE("QEMU_STDERR: %s", buf);
        }
    }
    close(read_fd);
    return NULL;
}

static void hook_stderr_to_logcat(void) {
    if (g_stderr_hooked) return;
    int pipefd[2];
    if (pipe(pipefd) == 0) {
        dup2(pipefd[1], STDERR_FILENO);
        close(pipefd[1]);
        setvbuf(stderr, NULL, _IONBF, 0);
        int *rfd = malloc(sizeof(int));
        if (rfd) {
            *rfd = pipefd[0];
            pthread_t t;
            if (pthread_create(&t, NULL, stderr_log_thread, rfd) == 0) {
                pthread_detach(t);
                g_stderr_hooked = true;
            } else {
                close(pipefd[0]);
                free(rfd);
            }
        }
    }
}

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
    (void)board;
    /* devos50/qemu-ios w tym buildzie ma zarejestrowana maszyne ipod-touch-2g */
    return "ipod-touch-2g";
}

static bool file_exists(const char *path) {
    return path != NULL && access(path, R_OK) == 0;
}

static void *vm_worker_loop(void *arg) {
    (void)arg;
    hook_stderr_to_logcat();

    char kernel_path[600];
    char dtb_path[600];
    char rootfs_path[600];
    char ram_arg[32];
    char smp_arg[32];
    char drive_arg[700];

    snprintf(kernel_path, sizeof(kernel_path), "%s/kernelcache", g_container_path);
    snprintf(dtb_path, sizeof(dtb_path), "%s/devicetree.img3", g_container_path);
    snprintf(rootfs_path, sizeof(rootfs_path), "%s/rootfs.dmg", g_container_path);

    int safe_ram = g_ram_mb > 0 ? g_ram_mb : 128;
    int safe_smp = g_cpu_cores > 0 ? g_cpu_cores : 1;

    snprintf(ram_arg, sizeof(ram_arg), "%dM", safe_ram);
    snprintf(smp_arg, sizeof(smp_arg), "%d", safe_smp);
    snprintf(drive_arg, sizeof(drive_arg), "file=%s,format=raw,if=none,id=nand0", rootfs_path);

    const char *machine = resolve_machine_type(g_board_config);

    LOGI("Checking container files in: %s", g_container_path);
    LOGI("kernelcache=%d, devicetree.img3=%d, rootfs.dmg=%d",
         file_exists(kernel_path), file_exists(dtb_path), file_exists(rootfs_path));

    char *argv[20];
    int argc = 0;

    argv[argc++] = "qemu-system-arm";
    argv[argc++] = "-M";
    argv[argc++] = (char *)machine;
    argv[argc++] = "-m";
    argv[argc++] = ram_arg;
    argv[argc++] = "-smp";
    argv[argc++] = smp_arg;

    if (file_exists(kernel_path)) {
        argv[argc++] = "-kernel";
        argv[argc++] = kernel_path;
    } else {
        LOGE("Missing kernelcache at %s", kernel_path);
    }

    if (file_exists(dtb_path)) {
        argv[argc++] = "-dtb";
        argv[argc++] = dtb_path;
    } else {
        LOGE("Missing devicetree.img3 at %s", dtb_path);
    }

    if (file_exists(rootfs_path)) {
        argv[argc++] = "-drive";
        argv[argc++] = drive_arg;
    } else {
        LOGE("Missing rootfs.dmg at %s", rootfs_path);
    }

    argv[argc++] = "-serial";
    argv[argc++] = "null";
    argv[argc++] = "-display";
    argv[argc++] = "none";
    argv[argc] = NULL;

    LOGI("Booting QEMU-iOS machine=%s board=%s ram=%s smp=%s argc=%d",
         machine, g_board_config, ram_arg, smp_arg, argc);

    int init_rc = qemu_init(argc, argv, NULL);
    LOGI("qemu_init returned: %d", init_rc);
    if (init_rc == 0) {
        qemu_main_loop();
    }
    qemu_cleanup();

    pthread_mutex_lock(&g_lock);
    g_vm_running = false;
    pthread_mutex_unlock(&g_lock);

    LOGI("QEMU-iOS main loop exited cleanly.");
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

    const char *c_path = container_path ? (*env)->GetStringUTFChars(env, container_path, NULL) : NULL;
    const char *c_board = board_config ? (*env)->GetStringUTFChars(env, board_config, NULL) : NULL;

    strncpy(g_container_path, c_path ? c_path : "", sizeof(g_container_path) - 1);
    g_container_path[sizeof(g_container_path) - 1] = '\0';

    strncpy(g_board_config, (c_board && c_board[0] != '\0') ? c_board : "ipod-touch-2g", sizeof(g_board_config) - 1);
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
        pthread_mutex_lock(&g_lock);
        g_vm_running = false;
        pthread_mutex_unlock(&g_lock);
        return JNI_FALSE;
    }

    pthread_detach(g_vm_thread);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_codythelildev_aos_QemuNativeBridge_nativeStopVm(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_lock);
    bool running = g_vm_running;
    pthread_mutex_unlock(&g_lock);

    if (!running) return;
    qemu_system_shutdown_request(1);
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
