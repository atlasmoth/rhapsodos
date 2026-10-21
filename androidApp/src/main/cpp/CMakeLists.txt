cmake_minimum_required(VERSION 3.22.1)
project(rhapsodos_phonon LANGUAGES C)

add_library(rhapsodos_phonon SHARED PhononBridge.c)
target_include_directories(rhapsodos_phonon PRIVATE ../../../../native/phonon/include)
target_compile_options(rhapsodos_phonon PRIVATE -Wall -Wextra -Werror)
target_link_options(rhapsodos_phonon PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(rhapsodos_phonon PRIVATE dl)
