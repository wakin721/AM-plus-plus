#include <cstddef>

#include "UsbDirectBufferSizing.h"

static_assert(usb_direct_buffer::ringBytesForDuration(48000, 8, 50) == 19200);
static_assert(usb_direct_buffer::ringBytesForDuration(48000, 8, 500) == 192000);
static_assert(usb_direct_buffer::ringBytesForDuration(48000, 8, 1000) == 384000);

static_assert(usb_direct_buffer::isoUrbCountForTarget(8000, 1, 0, 4) == 4);
static_assert(usb_direct_buffer::isoUrbCountForTarget(8000, 1, 2, 4) == 4);
static_assert(usb_direct_buffer::isoUrbCountForTarget(8000, 1, 4, 4) == 8);
static_assert(usb_direct_buffer::isoUrbCountForTarget(8000, 1, 8, 4) == 16);
static_assert(usb_direct_buffer::isoUrbCountForTarget(8000, 1, 16, 4) == 32);

static_assert(usb_direct_buffer::isoUrbCountForTarget(1000, 1, 2, 4) == 1);
static_assert(usb_direct_buffer::isoUrbCountForTarget(1000, 1, 4, 4) == 1);
static_assert(usb_direct_buffer::isoUrbCountForTarget(1000, 1, 8, 4) == 2);
static_assert(usb_direct_buffer::isoUrbCountForTarget(1000, 1, 16, 4) == 4);
