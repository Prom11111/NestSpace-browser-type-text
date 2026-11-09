cmake_minimum_required(VERSION 3.22.1)

project(nestspace_runtime LANGUAGES CXX)

add_library(
    nestspace_runtime
    SHARED
    native_environment.cpp
    native_environment_state.cpp
    native_libc_hooks.cpp
    native_elf_hooks.cpp
)

target_compile_features(nestspace_runtime PRIVATE cxx_std_17)
target_compile_options(
    nestspace_runtime
    PRIVATE
    -Wall
    -Wextra
    -Werror
    -fvisibility=hidden
)
target_link_options(
    nestspace_runtime
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)
target_link_libraries(nestspace_runtime PRIVATE log)
