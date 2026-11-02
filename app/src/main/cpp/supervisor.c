// Tanuki process session owner. Invoked only with application-owned arguments.
#define _GNU_SOURCE
#include <unistd.h>
#include <sys/prctl.h>
#include <sys/types.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
int main(int argc, char **argv) {
    if (argc < 2) return 64;
    pid_t parent = getppid();
    if (setsid() < 0) { perror("setsid"); return 71; }
    if (prctl(PR_SET_PDEATHSIG, SIGKILL) < 0 || getppid() != parent) return 71;
    fprintf(stderr, "TANUKI_IDENTITY pid=%d ppid=%d uid=%d euid=%d gid=%d pgid=%d\n",
            getpid(), parent, getuid(), geteuid(), getgid(), getpgrp());
    execv(argv[1], &argv[1]);
    perror("execv");
    return 126;
}
