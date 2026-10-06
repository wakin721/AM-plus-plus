#pragma once
#include <cstdint>

// Test-only declarations for compiling the engine on Windows. These are not
// kernel ABI definitions: the ring test does not invoke USB ioctls.
#define USBDEVFS_CLAIMINTERFACE 1
#define USBDEVFS_DISCONNECT_CLAIM 2
#define USBDEVFS_DISCONNECT_CLAIM_IF_DRIVER 1
#define USBDEVFS_CONNECT 3
#define USBDEVFS_IOCTL 4
#define USBDEVFS_RELEASEINTERFACE 5
#define USBDEVFS_SETINTERFACE 6
#define USBDEVFS_CONTROL 7
#define USBDEVFS_SUBMITURB 8
#define USBDEVFS_REAPURB 9
#define USBDEVFS_DISCARDURB 10
#define USBDEVFS_GET_SPEED 11
#define USBDEVFS_URB_TYPE_ISO 0
#define USBDEVFS_URB_ISO_ASAP 2
struct usbdevfs_disconnect_claim { unsigned int interface, flags; char driver[256]; };
struct usbdevfs_ioctl { int ifno, ioctl_code; void* data; };
struct usbdevfs_setinterface { unsigned int interface, altsetting; };
struct usbdevfs_ctrltransfer {
    uint8_t bRequestType, bRequest;
    uint16_t wValue, wIndex, wLength;
    unsigned int timeout;
    void* data;
};
struct usbdevfs_iso_packet_desc { unsigned int length, actual_length, status; };
struct usbdevfs_urb {
    unsigned char type, endpoint;
    int status;
    unsigned int flags;
    void* buffer;
    int buffer_length, actual_length, start_frame, number_of_packets, error_count;
    unsigned int signr;
    void* usercontext;
    usbdevfs_iso_packet_desc iso_frame_desc[1];
};
