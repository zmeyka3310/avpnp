/*
 * avpnp_tun — create and configure a per-client TUN interface, then hand its fd to the
 * requesting process over an abstract Unix socket (SCM_RIGHTS).
 *
 * Runs as root (invoked via su by avpnp's broker). Deliberately tiny: it owns no policy and
 * makes no routing decisions. Everything it does is parameterised on the command line by avpnp.
 *
 * Usage:
 *   avpnp_tun --connect <abstract-socket-name> --if <ifname> [--mtu <n>]
 *             [--addr <cidr>] [--table <id>]
 *
 * The interface is not persistent: it lives exactly as long as the receiving process keeps the
 * transferred fd open.
 */

#define _GNU_SOURCE

#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <net/if.h>
#include <linux/if_tun.h>

#define LOG(fmt, ...) fprintf(stderr, "avpnp-tun: " fmt "\n", ##__VA_ARGS__)

static int connect_abstract(const char *name) {
    int fd;
    struct sockaddr_un addr;
    socklen_t len;
    size_t n;

    if (name[0] == '@') {
        name++;
    }
    n = strlen(name);
    if (n == 0 || n + 1 > sizeof(addr.sun_path)) {
        errno = EINVAL;
        return -1;
    }

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        return -1;
    }

    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    /* Leading NUL selects the abstract namespace, matching Android's LocalSocketAddress. */
    memcpy(addr.sun_path + 1, name, n);
    len = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + n);

    if (connect(fd, (struct sockaddr *)&addr, len) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

static int send_fd(int sock, int fd_to_send) {
    char buf[1] = { 'F' };
    char cmsgbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    struct msghdr msg;
    struct cmsghdr *cmsg;

    memset(cmsgbuf, 0, sizeof(cmsgbuf));
    memset(&msg, 0, sizeof(msg));

    iov.iov_base = buf;
    iov.iov_len = sizeof(buf);

    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsgbuf;
    msg.msg_controllen = sizeof(cmsgbuf);

    cmsg = CMSG_FIRSTHDR(&msg);
    cmsg->cmsg_level = SOL_SOCKET;
    cmsg->cmsg_type = SCM_RIGHTS;
    cmsg->cmsg_len = CMSG_LEN(sizeof(int));
    memcpy(CMSG_DATA(cmsg), &fd_to_send, sizeof(int));

    return sendmsg(sock, &msg, 0) >= 0 ? 0 : -1;
}

/*
 * Android has no /bin/sh, so glibc's system() cannot be used. Run through /system/bin/sh
 * explicitly.
 */
static int run(const char *cmd) {
    int status = 0;
    pid_t pid = fork();

    if (pid < 0) {
        LOG("fork failed: %s", strerror(errno));
        return -1;
    }
    if (pid == 0) {
        execl("/system/bin/sh", "sh", "-c", cmd, (char *)NULL);
        _exit(127);
    }

    if (waitpid(pid, &status, 0) < 0) {
        LOG("waitpid failed: %s", strerror(errno));
        return -1;
    }

    LOG("cmd(%s) status=%d", cmd, status);
    return status;
}

static void usage(void) {
    fprintf(stderr,
            "usage: avpnp_tun --connect NAME --if IFNAME [--mtu N] [--addr CIDR] [--table ID]\n");
}

int main(int argc, char **argv) {
    const char *sockname = NULL;
    const char *ifname = NULL;
    const char *addr = NULL;
    const char *table = NULL;
    int mtu = 1500;
    char cmd[512];
    struct ifreq ifr;
    int tun, sock, i;

    for (i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--connect") && i + 1 < argc) {
            sockname = argv[++i];
        } else if (!strcmp(argv[i], "--if") && i + 1 < argc) {
            ifname = argv[++i];
        } else if (!strcmp(argv[i], "--mtu") && i + 1 < argc) {
            mtu = atoi(argv[++i]);
        } else if (!strcmp(argv[i], "--addr") && i + 1 < argc) {
            addr = argv[++i];
        } else if (!strcmp(argv[i], "--table") && i + 1 < argc) {
            table = argv[++i];
        } else {
            LOG("unknown argument: %s", argv[i]);
            usage();
            return 2;
        }
    }

    if (sockname == NULL || ifname == NULL) {
        usage();
        return 2;
    }

    tun = open("/dev/net/tun", O_RDWR);
    if (tun < 0) {
        LOG("open /dev/net/tun failed: %s", strerror(errno));
        return 3;
    }

    memset(&ifr, 0, sizeof(ifr));
    ifr.ifr_flags = IFF_TUN | IFF_NO_PI;
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);

    if (ioctl(tun, TUNSETIFF, &ifr) < 0) {
        LOG("TUNSETIFF(%s) failed: %s", ifname, strerror(errno));
        close(tun);
        return 4;
    }

    snprintf(cmd, sizeof(cmd), "ip link set %s up mtu %d", ifr.ifr_name, mtu);
    run(cmd);

    if (addr != NULL) {
        snprintf(cmd, sizeof(cmd), "ip addr replace %s dev %s", addr, ifr.ifr_name);
        run(cmd);
    }

    if (table != NULL) {
        snprintf(cmd, sizeof(cmd), "ip route replace default dev %s table %s", ifr.ifr_name, table);
        run(cmd);
    }

    sock = connect_abstract(sockname);
    if (sock < 0) {
        LOG("connect(%s) failed: %s", sockname, strerror(errno));
        close(tun);
        return 5;
    }

    if (send_fd(sock, tun) < 0) {
        LOG("sendmsg failed: %s", strerror(errno));
        close(sock);
        close(tun);
        return 6;
    }

    LOG("handed off fd=%d if=%s mtu=%d", tun, ifr.ifr_name, mtu);

    close(sock);
    /* The receiver holds a duplicate of the fd; the interface outlives this process. */
    close(tun);
    return 0;
}
