#include "UsbPcmPacking.h"

using usb_pcm::leftJustifiedSampleBits;
using usb_pcm::littleEndianSampleByte;

static_assert(leftJustifiedSampleBits(0x7fffff, 4, 24) == 0x7fffff00ULL);
static_assert(leftJustifiedSampleBits(-1, 4, 24) == 0xffffff00ULL);
static_assert(leftJustifiedSampleBits(0x7fffff, 3, 24) == 0x7fffffULL);
static_assert(leftJustifiedSampleBits(-0x800000, 4, 24) == 0x80000000ULL);
static_assert(leftJustifiedSampleBits(0x7fff, 3, 16) == 0x7fff00ULL);
static_assert(leftJustifiedSampleBits(0x7ffff, 3, 20) == 0x7ffff0ULL);
static_assert(littleEndianSampleByte(0x7fffff, 4, 24, 0) == 0x00);
static_assert(littleEndianSampleByte(0x7fffff, 4, 24, 1) == 0xff);
static_assert(littleEndianSampleByte(0x7fffff, 4, 24, 2) == 0xff);
static_assert(littleEndianSampleByte(0x7fffff, 4, 24, 3) == 0x7f);
