#include "UsbPcmPacking.h"

using usb_pcm::leftJustifiedSampleBits;

static_assert(leftJustifiedSampleBits(0x7fffff, 4, 24) == 0x7fffff00ULL);
static_assert(leftJustifiedSampleBits(-1, 4, 24) == 0xffffff00ULL);
static_assert(leftJustifiedSampleBits(0x7fffff, 3, 24) == 0x7fffffULL);
static_assert(leftJustifiedSampleBits(-0x800000, 4, 24) == 0x80000000ULL);
