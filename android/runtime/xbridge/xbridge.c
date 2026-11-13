/*
 * xbridge.c - input injection for the SWAT 4 launcher.
 *
 * SWAT 4 is a 32-bit Windows game (DirectX 8.1/9) that runs under Wine on
 * ARM64 through Box86.  Wine talks to the X server, so every touch event can
 * be injected at the X11 level with XTest - no engine-side hook is needed
 * (unlike the UT2004 launcher, which patches the engine's own SDL viewport).
 *
 * Protocol: 8 bytes per message, same as the UT2004 input bridge.
 *
 *   [0]     type
 *   [1..2]  code   (X11 keysym for MSG_KEY, button number for MSG_BUTTON)
 *   [3..6]  value  (int32 little endian)
 *   [7]     unused
 *
 *   MSG_KEY      1   keysym, value = 1 press / 0 release
 *   MSG_BUTTON    2   button 1..5, value = 1 press / 0 release
 *   MSG_MOTION    3   relative dx/dy packed in the value
 *   MSG_PAUSE     4   pause the game (ignored here, handled by the app)
 *   MSG_POSITION  5   absolute pointer position
 *   MSG_TEXT      6   one unicode scalar
 *
 * The bridge connects back to the Android app (the app is the server) and
 * injects everything with XTestFake*Event.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

#include <X11/Xlib.h>
#include <X11/keysym.h>
#include <X11/extensions/XTest.h>

#define MSG_KEY     1
#define MSG_BUTTON   2
#define MSG_MOTION   3
#define MSG_PAUSE    4
#define MSG_POSITION 5
#define MSG_TEXT     6

static Display *dpy;

static void logmsg(const char *fmt, ...)
{
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

static int connect_to_app(int port)
{
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    return fd;
}

static void handle(uint8_t type, uint16_t code, int32_t value)
{
    switch (type) {
    case MSG_KEY: {
        KeyCode kc = XKeysymToKeycode(dpy, (KeySym)code);
        if (kc == 0) {
            logmsg("xbridge: keysym 0x%x unknown", code);
            break;
        }
        XTestFakeKeyEvent(dpy, kc, value ? True : False, CurrentTime);
        XFlush(dpy);
        break;
    }
    case MSG_BUTTON: {
        /* 1 left, 2 middle, 3 right, 4 wheel up, 5 wheel down */
        unsigned int button = code;
        if (button >= 1 && button <= 5) {
            XTestFakeButtonEvent(dpy, button, value ? True : False, CurrentTime);
            XFlush(dpy);
        }
        break;
    }
    case MSG_MOTION: {
        int dx = (int16_t)(value & 0xffff);
        int dy = (int16_t)((uint32_t)value >> 16);
        if (dx || dy) {
            XTestFakeRelativeMotionEvent(dpy, dx, dy, CurrentTime);
            XFlush(dpy);
        }
        break;
    }
    case MSG_POSITION: {
        int x = (uint16_t)(value & 0xffff);
        int y = (uint16_t)((uint32_t)value >> 16);
        XTestFakeMotionEvent(dpy, -1, x, y, CurrentTime);
        XFlush(dpy);
        break;
    }
    case MSG_TEXT: {
        /* one unicode scalar: find a keysym that produces it and type it */
        KeySym ks = 0;
        if (value < 0x80) {
            ks = (KeySym)value;
        } else {
            char utf[8];
            int n = 0;
            uint32_t c = (uint32_t)value;
            if (c < 0x800) {
                utf[n++] = (char)(0xc0 | (c >> 6));
                utf[n++] = (char)(0x80 | (c & 0x3f));
            } else if (c < 0x10000) {
                utf[n++] = (char)(0xe0 | (c >> 12));
                utf[n++] = (char)(0x80 | ((c >> 6) & 0x3f));
                utf[n++] = (char)(0x80 | (c & 0x3f));
            } else {
                utf[n++] = (char)(0xf0 | (c >> 18));
                utf[n++] = (char)(0x80 | ((c >> 12) & 0x3f));
                utf[n++] = (char)(0x80 | ((c >> 6) & 0x3f));
                utf[n++] = (char)(0x80 | (c & 0x3f));
            }
            utf[n] = 0;
            ks = XStringToKeysym(utf);
        }
        if (ks == NoSymbol) break;
        KeyCode kc = XKeysymToKeycode(dpy, ks);
        if (kc == 0) break;
        XTestFakeKeyEvent(dpy, kc, True, CurrentTime);
        XTestFakeKeyEvent(dpy, kc, False, CurrentTime);
        XFlush(dpy);
        break;
    }
    default:
        break;
    }
}

int main(int argc, char **argv)
{
    const char *port_env = getenv("SWAT4_INPUT_PORT");
    int port = port_env ? atoi(port_env) : 0;
    if (!port && argc > 1) port = atoi(argv[1]);
    if (!port) {
        logmsg("xbridge: no input port");
        return 2;
    }

    dpy = XOpenDisplay(NULL);
    if (!dpy) {
        logmsg("xbridge: cannot open display");
        return 3;
    }
    if (!XTestQueryExtension(dpy, NULL, NULL, NULL, NULL)) {
        logmsg("xbridge: XTest not available");
        return 4;
    }

    int fd = -1;
    for (int attempt = 0; attempt < 60; attempt++) {
        fd = connect_to_app(port);
        if (fd >= 0) break;
        usleep(250000);
    }
    if (fd < 0) {
        logmsg("xbridge: cannot reach the app");
        return 5;
    }
    logmsg("xbridge: connected");

    uint8_t msg[8];
    size_t got = 0;
    for (;;) {
        ssize_t n = read(fd, msg + got, sizeof(msg) - got);
        if (n <= 0) {
            if (n < 0 && errno == EINTR) continue;
            break;
        }
        got += (size_t)n;
        if (got < sizeof(msg)) continue;
        got = 0;
        uint16_t code = (uint16_t)(msg[1] | (msg[2] << 8));
        int32_t value = (int32_t)((uint32_t)msg[3] | ((uint32_t)msg[4] << 8) |
                                  ((uint32_t)msg[5] << 16) | ((uint32_t)msg[6] << 24));
        handle(msg[0], code, value);
    }
    close(fd);
    XCloseDisplay(dpy);
    return 0;
}
