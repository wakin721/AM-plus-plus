#include "UsbDirectUacControl.h"

constexpr auto kRate44 = usb_direct_uac::uac1RatePayload(44100);
static_assert(kRate44[0] == 0x44);
static_assert(kRate44[1] == 0xAC);
static_assert(kRate44[2] == 0x00);

constexpr auto kRate192 = usb_direct_uac::uac2RatePayload(192000);
static_assert(kRate192[0] == 0x00);
static_assert(kRate192[1] == 0xEE);
static_assert(kRate192[2] == 0x02);
static_assert(kRate192[3] == 0x00);

static_assert(usb_direct_uac::uac2ControlIndex(7, 1) == 0x0701);
static_assert(usb_direct_uac::acceptUac1SetCurResult(3, false));
static_assert(usb_direct_uac::acceptUac1SetCurResult(-1, true));
static_assert(!usb_direct_uac::acceptUac1SetCurResult(-1, false));
