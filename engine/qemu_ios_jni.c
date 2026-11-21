#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <glib.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include "qemu/osdep.h"
#include "qom/object.h"
#include "ui/console.h"
#include "ui/input.h"

#define LOG_TAG "QemuIosEngine"

extern int qemu_init(int argc, char **argv, char **envp);
extern void qemu_main_loop(void);
extern void qemu_cleanup(void);
extern void qemu_system_shutdown_request(int reason);
extern Object *qdev_get_machine(void);

static ANativeWindow *g_window = NULL;
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_vm_thread;
static volatile bool g_vm_running = false;
static bool g_stderr_hooked = false;
static int g_saved_kotlin_stderr_fd = -1;

static char g_container_path[512];
static char g_board_config[64];
static int g_cpu_cores = 2;
static int g_ram_mb = 512;
static float g_res_scale = 0.75f;
static int g_renderer_id = 0;
static bool g_net_enabled = true;

static void write_both_logs(int prio, const char *fmt, ...) {
    char msg[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);

    __android_log_print(prio, LOG_TAG, "%s", msg);

    if (g_saved_kotlin_stderr_fd >= 0) {
        dprintf(g_saved_kotlin_stderr_fd, "[QemuIosEngine] %s\n", msg);
    }
}

#define LOGI(...) write_both_logs(ANDROID_LOG_INFO, __VA_ARGS__)
#define LOGE(...) write_both_logs(ANDROID_LOG_ERROR, __VA_ARGS__)

static void aos_glib_log_handler(const gchar *log_domain,
                                 GLogLevelFlags log_level,
                                 const gchar *message,
                                 gpointer user_data) {
    (void)log_level;
    (void)user_data;
    if (g_saved_kotlin_stderr_fd >= 0) {
        dprintf(g_saved_kotlin_stderr_fd, "[GLib:%s] %s\n",
                log_domain ? log_domain : "default",
                message ? message : "(null)");
    }
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "[GLib:%s] %s",
                        log_domain ? log_domain : "default",
                        message ? message : "(null)");
}

static void on_process_exit(void) {
    if (g_saved_kotlin_stderr_fd >= 0) {
        dprintf(g_saved_kotlin_stderr_fd, "[QemuIosEngine] PROCESS EXITING (atexit triggered)\n");
    }
    __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "PROCESS EXITING (atexit triggered)");
}

static void on_fatal_signal(int sig, siginfo_t *info, void *ucontext) {
    (void)ucontext;
    if (g_saved_kotlin_stderr_fd >= 0) {
        dprintf(
            g_saved_kotlin_stderr_fd,
            "[QemuIosEngine] FATAL SIGNAL %d (%s) at addr=%p\n",
            sig,
            strsignal(sig),
            info ? info->si_addr : NULL
        );
    }
    __android_log_print(
        ANDROID_LOG_FATAL,
        LOG_TAG,
        "FATAL SIGNAL %d (%s) at addr=%p",
        sig,
        strsignal(sig),
        info ? info->si_addr : NULL
    );
    _exit(128 + sig);
}

static void install_crash_handlers(void) {
    atexit(on_process_exit);
    g_log_set_default_handler(aos_glib_log_handler, NULL);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = on_fatal_signal;
    sa.sa_flags = SA_SIGINFO | SA_RESETHAND;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, NULL);
    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGILL, &sa, NULL);
    sigaction(SIGBUS, &sa, NULL);
}

static void *stderr_tee_thread(void *arg) {
    int read_fd = *(int *)arg;
    free(arg);
    char buf[512];
    while (1) {
        ssize_t n = read(read_fd, buf, sizeof(buf) - 1);
        if (n <= 0) break;

        if (g_saved_kotlin_stderr_fd >= 0) {
            ssize_t ignored = write(g_saved_kotlin_stderr_fd, buf, (size_t)n);
            (void)ignored;
        }

        buf[n] = '\0';
        while (n > 0 && (buf[n - 1] == '\n' || buf[n - 1] == '\r')) {
            buf[--n] = '\0';
        }
        if (n > 0) {
            __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "QEMU_STDERR: %s", buf);
        }
    }
    close(read_fd);
    return NULL;
}

static void hook_stderr_preserve_file(void) {
    if (g_stderr_hooked) return;

    g_saved_kotlin_stderr_fd = dup(STDERR_FILENO);

    int pipefd[2];
    if (pipe(pipefd) == 0) {
        dup2(pipefd[1], STDERR_FILENO);
        dup2(pipefd[1], STDOUT_FILENO);
        close(pipefd[1]);
        setvbuf(stderr, NULL, _IONBF, 0);
        setvbuf(stdout, NULL, _IONBF, 0);

        int *rfd = malloc(sizeof(int));
        if (rfd) {
            *rfd = pipefd[0];
            pthread_t t;
            if (pthread_create(&t, NULL, stderr_tee_thread, rfd) == 0) {
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

static DisplaySurface *g_qemu_surface = NULL;

static void aos_dpy_gfx_update(DisplayChangeListener *dcl, int x, int y, int w, int h) {
    (void)dcl;
    (void)x;
    (void)y;
    (void)w;
    (void)h;
    if (g_qemu_surface) {
        int width = surface_width(g_qemu_surface);
        int height = surface_height(g_qemu_surface);
        const uint32_t *data = (const uint32_t *)surface_data(g_qemu_surface);
        aos_push_framebuffer(data, width, height);
    }
}

static void aos_dpy_gfx_switch(DisplayChangeListener *dcl, struct DisplaySurface *new_surface) {
    (void)dcl;
    g_qemu_surface = new_surface;
    if (g_qemu_surface) {
        int width = surface_width(g_qemu_surface);
        int height = surface_height(g_qemu_surface);
        const uint32_t *data = (const uint32_t *)surface_data(g_qemu_surface);
        aos_push_framebuffer(data, width, height);
    }
}

static void aos_dpy_refresh(DisplayChangeListener *dcl) {
    graphic_hw_update(dcl->con);
}

static const DisplayChangeListenerOps aos_dpy_ops = {
    .dpy_name = "aos-android-surface",
    .dpy_gfx_update = aos_dpy_gfx_update,
    .dpy_gfx_switch = aos_dpy_gfx_switch,
    .dpy_refresh = aos_dpy_refresh,
};

static DisplayChangeListener g_aos_dcl = {
    .ops = &aos_dpy_ops,
};

static bool file_exists(const char *path) {
    return path != NULL && access(path, R_OK) == 0;
}

static void ensure_nand_directory(const char *nand_dir) {
    struct stat st;
    if (stat(nand_dir, &st) != 0) {
        if (mkdir(nand_dir, 0755) != 0 && errno != EEXIST) {
            LOGE("Failed to create nand directory %s: %s", nand_dir, strerror(errno));
            return;
        }
    }

    char geom_path[700];
    snprintf(geom_path, sizeof(geom_path), "%s/geometry.json", nand_dir);
    FILE *f = fopen(geom_path, "wb");
    if (f) {
        fprintf(f,
                "{\n"
                "  \"page_bytes\": 8192,\n"
                "  \"oob_bytes\": 16,\n"
                "  \"spare_bytes\": 16,\n"
                "  \"meta_bytes\": 16,\n"
                "  \"pages_per_block\": 128,\n"
                "  \"blocks_per_ce\": 1024,\n"
                "  \"blocks_per_bank\": 1024,\n"
                "  \"blocks\": 1024,\n"
                "  \"total_blocks\": 1024,\n"
                "  \"ce_count\": 1,\n"
                "  \"banks\": 1,\n"
                "  \"banks_per_ce\": 1,\n"
                "  \"dies_per_ce\": 1,\n"
                "  \"planes_per_die\": 1,\n"
                "  \"sectors_per_page\": 16,\n"
                "  \"lba_bytes\": 512\n"
                "}\n");
        fclose(f);
        LOGI("Wrote NAND geometry.json (with blocks_per_ce=1024) at %s", geom_path);
    } else {
        LOGE("Failed to write %s: %s", geom_path, strerror(errno));
    }
}

static void resolve_ios_machine(const char *board, char *out_machine, size_t out_len) {
    const char *target = "iPhone-4";
    if (board != NULL) {
        if (strcasecmp(board, "n90ap") == 0) target = "iPhone-4";
        else if (strcasecmp(board, "n81ap") == 0) target = "iPod-Touch-4G";
        else if (strcasecmp(board, "k48ap") == 0) target = "ipad1";
        else if (strcasecmp(board, "n88ap") == 0) target = "n88";
        else if (strcasecmp(board, "n18ap") == 0) target = "n18";
        else if (strcasecmp(board, "n72ap") == 0) target = "iPod-Touch";
        else if (strcasecmp(board, "m68ap") == 0) target = "iPhone-2G";
        else if (strcasecmp(board, "n45ap") == 0) target = "iPod-Touch-1G";
        else if (strcasecmp(board, "n41ap") == 0) target = "iPhone-4";
    }

    strncpy(out_machine, target, out_len - 1);
    out_machine[out_len - 1] = '\0';
}

static void append_prop_str(char *machine_arg, size_t max_len, const char *prop_name, const char *value) {
    if (!value || value[0] == '\0') return;
    strncat(machine_arg, ",", max_len - strlen(machine_arg) - 1);
    strncat(machine_arg, prop_name, max_len - strlen(machine_arg) - 1);
    strncat(machine_arg, "=", max_len - strlen(machine_arg) - 1);
    strncat(machine_arg, value, max_len - strlen(machine_arg) - 1);
}

static void *vm_worker_loop(void *arg) {
    (void)arg;
    hook_stderr_preserve_file();
    install_crash_handlers();

    char machine[64];
    resolve_ios_machine(g_board_config, machine, sizeof(machine));

    char kernel_path[600];
    char kernel_raw_path[600];
    char dtb_path[600];
    char bootrom_path[600];
    char iboot_path[600];
    char nor_path[600];
    char nand_dir_path[600];
    char machine_arg[2048];

    snprintf(kernel_path, sizeof(kernel_path), "%s/kernelcache", g_container_path);
    snprintf(kernel_raw_path, sizeof(kernel_raw_path), "%s/kernelcache.raw", g_container_path);
    snprintf(dtb_path, sizeof(dtb_path), "%s/devicetree.img3", g_container_path);
    snprintf(bootrom_path, sizeof(bootrom_path), "%s/bootrom.bin", g_container_path);
    snprintf(iboot_path, sizeof(iboot_path), "%s/iboot.img3", g_container_path);
    snprintf(nor_path, sizeof(nor_path), "%s/nor.bin", g_container_path);
    snprintf(nand_dir_path, sizeof(nand_dir_path), "%s/nand_storage", g_container_path);

    ensure_nand_directory(nand_dir_path);

    const char *selected_kernel = file_exists(kernel_raw_path) ? kernel_raw_path : kernel_path;

    snprintf(machine_arg, sizeof(machine_arg), "%s", machine);
    if (file_exists(bootrom_path)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "bootrom", bootrom_path);
    } else if (file_exists(iboot_path)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "iboot", iboot_path);
    } else if (file_exists(selected_kernel)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "kboot", selected_kernel);
    } else {
        LOGE("CRITICAL: Missing kernelcache / iboot / bootrom in %s", g_container_path);
    }

    if (file_exists(dtb_path)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "dtb", dtb_path);
    }
    if (file_exists(nor_path)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "nor", nor_path);
    }
    if (file_exists(nand_dir_path)) {
        append_prop_str(machine_arg, sizeof(machine_arg), "nand", nand_dir_path);
    }

    append_prop_str(machine_arg, sizeof(machine_arg), "graphics", "on");
    append_prop_str(machine_arg, sizeof(machine_arg), "wifi", "off");

    const char *ram_str = (strcmp(machine, "iPhone-4") == 0 ||
                           strcmp(machine, "iPod-Touch-4G") == 0 ||
                           strcmp(machine, "ipad1") == 0) ? "512M" : "256M";

    LOGI("Checking container files in: %s", g_container_path);
    LOGI("kboot=%s (exists=%d), dtb=%d, nand_dir=%s (exists=%d)",
         selected_kernel, file_exists(selected_kernel),
         file_exists(dtb_path), nand_dir_path, file_exists(nand_dir_path));

    char *argv[20];
    int argc = 0;

    argv[argc++] = "qemu-system-arm";
    argv[argc++] = "-M";
    argv[argc++] = machine_arg;
    argv[argc++] = "-accel";
    argv[argc++] = "tcg,thread=multi";
    argv[argc++] = "-m";
    argv[argc++] = (char *)ram_str;
    argv[argc++] = "-smp";
    argv[argc++] = "2";
    argv[argc++] = "-serial";
    argv[argc++] = "stdio";
    argv[argc++] = "-display";
    argv[argc++] = "none";
    argv[argc] = NULL;

    LOGI("Calling qemu_init (v5-blocks-per-ce): -M %s -accel tcg,thread=multi -m %s -smp 2 (argc=%d)", machine_arg, ram_str, argc);

    int init_rc = qemu_init(argc, argv, NULL);
    LOGI("qemu_init returned: %d", init_rc);

    if (init_rc == 0) {
        register_displaychangelistener(&g_aos_dcl);
        LOGI("DisplayChangeListener registered, entering qemu_main_loop()");
        qemu_main_loop();
        unregister_displaychangelistener(&g_aos_dcl);
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

    strncpy(g_board_config, (c_board && c_board[0] != '\0') ? c_board : "n90ap", sizeof(g_board_config) - 1);
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

    pthread_mutex_lock(&g_lock);
    bool running = g_vm_running;
    pthread_mutex_unlock(&g_lock);
    if (!running) return;

    Object *mach = qdev_get_machine();
    if (!mach) return;

    bool pressed = (is_pressed == JNI_TRUE);
    if (key_code == 1) {
        object_property_set_bool(mach, "button-home", pressed, NULL);
    } else if (key_code == 2) {
        object_property_set_bool(mach, "button-hold", pressed, NULL);
    }
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

    pthread_mutex_lock(&g_lock);
    bool running = g_vm_running;
    pthread_mutex_unlock(&g_lock);
    if (!running) return;

    int x_abs = (int)(normalized_x * 32767.0f);
    int y_abs = (int)(normalized_y * 32767.0f);
    if (x_abs < 0) x_abs = 0;
    if (x_abs > 32767) x_abs = 32767;
    if (y_abs < 0) y_abs = 0;
    if (y_abs > 32767) y_abs = 32767;

    qemu_input_queue_abs(NULL, INPUT_AXIS_X, x_abs, 0, 32767);
    qemu_input_queue_abs(NULL, INPUT_AXIS_Y, y_abs, 0, 32767);
    if (action == 0) {
        qemu_input_queue_btn(NULL, INPUT_BUTTON_LEFT, true);
    } else if (action == 1) {
        qemu_input_queue_btn(NULL, INPUT_BUTTON_LEFT, false);
    }
    qemu_input_event_sync();
}
