cmake_minimum_required(VERSION 3.22.1)

project("default_configs")

add_library(
    default_configs
    SHARED
    default_configs.cpp
)

find_library(
    log-lib
    log
)

# This is the missing part - link the android library
find_library(
    android-lib
    android
)

target_link_libraries(
    default_configs
    ${log-lib}
    ${android-lib}      # ← This fixes AAssetManager_fromJava
)
