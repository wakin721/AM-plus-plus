#pragma once
// Ring tests cannot reach a real device through the syscall boundary.
inline int ioctl(int, unsigned long, ...) { return -1; }
