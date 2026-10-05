# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
# Copyright (C) 2026 FebriCahyaa
#
# Host build:   cmake -S userspace -B build && cmake --build build
# Android (NDK):
#   cmake -S userspace -B out-arm64 \
#     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
#     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31
cmake_minimum_required(VERSION 3.18)
project(zkfc_userspace C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
add_compile_options(-Wall -Wextra -O2 -fstack-protector-strong -D_FORTIFY_SOURCE=2)

# UAPI header (<linux/zkfc.h>)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../kernel/include/uapi)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/include)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/lib)

add_library(zkfc STATIC
    lib/libzkfc.c
    lib/zk_token.c
    lib/zk_sysfs.c
    lib/zk_tweaks.c
    lib/zk_json.c)

add_executable(zkfctl zkfctl/main.c)
target_link_libraries(zkfctl zkfc)
# Position-independent executable (Android requires PIE).
set_target_properties(zkfctl PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_link_options(zkfctl PRIVATE -pie)

install(TARGETS zkfctl RUNTIME DESTINATION bin)
