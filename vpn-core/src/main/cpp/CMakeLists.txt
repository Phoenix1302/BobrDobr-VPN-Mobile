# BobrDobr VPN Mobile Client - C++ JNI Bridge CMake
# Project: БобрДобр VPN ("Центр гидробиологических инноваций")

cmake_minimum_required(VERSION 3.22.1)

project("vpn_core_jni" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 16 KB page size ELF alignment for Android 15+ (Pixel 8 / arm64-v8a)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

include_directories(include)

add_library(vpn_core_jni SHARED
    jni_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(vpn_core_jni
    ${log-lib}
    dl
)

target_link_options(vpn_core_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
