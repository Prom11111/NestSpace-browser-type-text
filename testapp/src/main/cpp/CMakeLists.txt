cmake_minimum_required(VERSION 3.22.1)

project(testapp_probe LANGUAGES CXX)

add_library(testapp_probe SHARED native_probe.cpp)
target_compile_features(testapp_probe PRIVATE cxx_std_17)
target_compile_options(
    testapp_probe
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)
target_link_options(
    testapp_probe
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(testapp_probe PRIVATE log)
