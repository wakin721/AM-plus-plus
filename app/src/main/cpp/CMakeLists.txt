cmake_minimum_required(VERSION 3.22.1)
project(ampp_audio LANGUAGES CXX)

add_library(
    ampp_audio
    SHARED
    UsbDirectUac.cpp
    UsbFeedbackClockCompileTest.cpp
    UsbPcmPackingCompileTest.cpp
)

target_compile_features(ampp_audio PRIVATE cxx_std_17)

find_library(log_lib log)

target_link_libraries(
    ampp_audio
    PRIVATE
    ${log_lib}
)
