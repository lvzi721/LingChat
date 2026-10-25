// LingChat root volume-key daemon.
//
// Reads /dev/input/event* directly (root), recognises configurable volume-key
// gestures, and starts LingChat's foreground wake service via `am`.
//
// Config is re-read from the app's private files dir so the UI can change
// gestures without root: /data/data/com.noiq.lingchat/files/rootd.conf

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <poll.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define MAX_DEV 16
#define LOG_PATH "/data/local/tmp/lingchat-rootd.log"
#define CONFIG_CANDIDATES 4
static const char *CONFIG_PATHS[CONFIG_CANDIDATES] = {
    "/data/data/com.noiq.lingchat/files/rootd.conf",
    "/data/data/com.noiq.lingchat/rootd.conf",
    "/data/user/0/com.noiq.lingchat/files/rootd.conf",
    "/data/user/0/com.noiq.lingchat/rootd.conf",
};
#define TARGET_COMPONENT "com.noiq.lingchat/com.noiq.lingchat.voicewake.VoiceWakeService"
#define TARGET_ACTION "com.noiq.lingchat.voicewake.WAKE"

struct config {
    int up_long;
    int up_double;
    int down_long;
    int down_double;
    int any_long;
    int any_double;
    int combo;
    int long_ms;
    int double_ms;
};

static struct config cfg = {
    .up_long = 1, .up_double = 0,
    .down_long = 1, .down_double = 1,
    .any_long = 0, .any_double = 0,
    .combo = 1,
    .long_ms = 800, .double_ms = 350,
};

static int dev_fds[MAX_DEV];
static int dev_count = 0;
static long long last_config_check = 0;
static time_t config_mtime = 0;

static long long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static void log_line(const char *fmt, ...) {
    FILE *f = fopen(LOG_PATH, "a");
    if (!f) return;
    time_t t = time(NULL);
    struct tm tm;
    localtime_r(&t, &tm);
    char stamp[16];
    strftime(stamp, sizeof(stamp), "%H:%M:%S", &tm);
    fprintf(f, "[%s] ", stamp);
    va_list ap;
    va_start(ap, fmt);
    vfprintf(f, fmt, ap);
    va_end(ap);
    fputc('\n', f);
    fclose(f);
}

static void load_config(void) {
    FILE *f = NULL;
    for (int i = 0; i < CONFIG_CANDIDATES && !f; i++) {
        f = fopen(CONFIG_PATHS[i], "r");
    }
    if (!f) return;
    char line[128];
    while (fgets(line, sizeof(line), f)) {
        char key[64];
        int value;
        if (sscanf(line, "%63[^=]=%d", key, &value) != 2) continue;
        if (!strcmp(key, "volume_up_long")) cfg.up_long = value;
        else if (!strcmp(key, "volume_up_double")) cfg.up_double = value;
        else if (!strcmp(key, "volume_down_long")) cfg.down_long = value;
        else if (!strcmp(key, "volume_down_double")) cfg.down_double = value;
        else if (!strcmp(key, "any_long")) cfg.any_long = value;
        else if (!strcmp(key, "any_double")) cfg.any_double = value;
        else if (!strcmp(key, "combo")) cfg.combo = value;
        else if (!strcmp(key, "long_ms")) cfg.long_ms = value;
        else if (!strcmp(key, "double_ms")) cfg.double_ms = value;
    }
    fclose(f);
    if (cfg.long_ms < 300) cfg.long_ms = 300;
    if (cfg.long_ms > 1500) cfg.long_ms = 1500;
    if (cfg.double_ms < 150) cfg.double_ms = 150;
    if (cfg.double_ms > 600) cfg.double_ms = 600;
}

static void maybe_reload_config(void) {
    long long now = now_ms();
    if (now - last_config_check < 2000) return;
    last_config_check = now;
    struct stat st;
    int found = 0;
    for (int i = 0; i < CONFIG_CANDIDATES; i++) {
        if (stat(CONFIG_PATHS[i], &st) == 0) {
            found = 1;
            break;
        }
    }
    if (!found) return;
    if (st.st_mtime != config_mtime) {
        config_mtime = st.st_mtime;
        load_config();
        log_line("config reloaded long=%d double=%d combo=%d", cfg.long_ms, cfg.double_ms, cfg.combo);
    }
}

static void trigger_wake(const char *gesture) {
    static long long last_trigger = 0;
    long long now = now_ms();
    if (now - last_trigger < 400) return;
    last_trigger = now;
    log_line("gesture=%s -> wake", gesture);

    pid_t pid = fork();
    if (pid == 0) {
        execl("/system/bin/am", "am", "start-foreground-service",
              "-n", TARGET_COMPONENT, "-a", TARGET_ACTION, (char *)NULL);
        _exit(127);
    } else if (pid > 0) {
        waitpid(pid, NULL, 0);
    }
}

static int open_devices(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) {
        log_line("open /dev/input failed: %s", strerror(errno));
        return 0;
    }
    struct dirent *entry;
    while ((entry = readdir(dir)) && dev_count < MAX_DEV) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_NONBLOCK);
        if (fd < 0) continue;
        unsigned long keybits[(KEY_MAX + 1) / (8 * sizeof(unsigned long)) + 1] = {0};
        if (ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(keybits)), keybits) < 0) {
            close(fd);
            continue;
        }
        int has_up = (keybits[KEY_VOLUMEUP / (8 * sizeof(unsigned long))] >>
                      (KEY_VOLUMEUP % (8 * sizeof(unsigned long)))) & 1;
        int has_down = (keybits[KEY_VOLUMEDOWN / (8 * sizeof(unsigned long))] >>
                        (KEY_VOLUMEDOWN % (8 * sizeof(unsigned long)))) & 1;
        if (!has_up && !has_down) {
            close(fd);
            continue;
        }
        char name[80] = {0};
        ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
        log_line("device %s name=%s up=%d down=%d", path, name, has_up, has_down);
        dev_fds[dev_count++] = fd;
    }
    closedir(dir);
    return dev_count;
}

int main(void) {
    log_line("lingchat-rootd starting");
    load_config();
    if (!open_devices()) {
        log_line("no volume-key device found, exit");
        return 1;
    }

    int up_down_at = 0, down_down_at = 0;
    int up_is_down = 0, down_is_down = 0;
    long long up_down_ms = 0, down_down_ms = 0;
    long long up_last_double = 0, down_last_double = 0;
    long long combo_at = 0;
    int up_long_fired = 0, down_long_fired = 0;

    for (;;) {
        maybe_reload_config();

        struct pollfd pfds[MAX_DEV];
        for (int i = 0; i < dev_count; i++) {
            pfds[i].fd = dev_fds[i];
            pfds[i].events = POLLIN;
        }
        int timeout = 50;
        if (up_is_down || down_is_down) timeout = 20;
        int ready = poll(pfds, dev_count, timeout);

        if (ready > 0) {
            for (int i = 0; i < dev_count; i++) {
                if (!(pfds[i].revents & POLLIN)) continue;
                struct input_event ev;
                while (read(dev_fds[i], &ev, sizeof(ev)) == sizeof(ev)) {
                    if (ev.type != EV_KEY) continue;
                    long long now = now_ms();
                    int is_up_key = ev.code == KEY_VOLUMEUP;
                    int is_down_key = ev.code == KEY_VOLUMEDOWN;
                    if (!is_up_key && !is_down_key) continue;

                    if (ev.value == 1) {
                        log_line("key %s down", is_up_key ? "UP" : "DOWN");
                        if (is_up_key) {
                            if (up_last_double && now - up_last_double <= cfg.double_ms) {
                                if (cfg.up_double) trigger_wake("volume_up_double");
                                else if (cfg.any_double) trigger_wake("any_double");
                                up_last_double = 0;
                            } else {
                                up_last_double = now;
                            }
                            up_is_down = 1;
                            up_down_ms = now;
                            up_long_fired = 0;
                            up_down_at = 1;
                        } else {
                            if (down_last_double && now - down_last_double <= cfg.double_ms) {
                                if (cfg.down_double) trigger_wake("volume_down_double");
                                else if (cfg.any_double) trigger_wake("any_double");
                                down_last_double = 0;
                            } else {
                                down_last_double = now;
                            }
                            down_is_down = 1;
                            down_down_ms = now;
                            down_long_fired = 0;
                            down_down_at = 1;
                        }
                        if (cfg.combo && up_down_at && down_down_at &&
                            llabs(up_down_ms - down_down_ms) <= 150) {
                            if (now - combo_at > 400) {
                                combo_at = now;
                                trigger_wake("combo");
                                // 组合手势已触发：抑制两个单键的长按/双击，避免同一动作响两次
                                up_long_fired = 1;
                                down_long_fired = 1;
                                up_last_double = 0;
                                down_last_double = 0;
                            }
                        }
                    } else if (ev.value == 0) {
                        log_line("key %s up", is_up_key ? "UP" : "DOWN");
                        if (is_up_key) { up_is_down = 0; up_down_at = 0; }
                        else { down_is_down = 0; down_down_at = 0; }
                    }
                }
            }
        }

        long long now = now_ms();
        if (up_is_down && !up_long_fired && now - up_down_ms >= cfg.long_ms) {
            up_long_fired = 1;
            if (cfg.up_long) trigger_wake("volume_up_long");
            else if (cfg.any_long) trigger_wake("any_long");
            up_last_double = 0;
        }
        if (down_is_down && !down_long_fired && now - down_down_ms >= cfg.long_ms) {
            down_long_fired = 1;
            if (cfg.down_long) trigger_wake("volume_down_long");
            else if (cfg.any_long) trigger_wake("any_long");
            down_last_double = 0;
        }
    }
    return 0;
}

