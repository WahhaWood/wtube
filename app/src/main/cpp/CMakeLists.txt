cmake_minimum_required(VERSION 3.22.1)

project(wtube_dpi C)

# byedpi (hufrea/byedpi, MIT) is vendored at the commit in byedpi/COMMIT; scripts/update-byedpi.sh refreshes it.
file(GLOB BYEDPI_SRC byedpi/*.c)

add_library(byedpi SHARED ${BYEDPI_SRC} byedpi-jni.c)
target_include_directories(byedpi PRIVATE byedpi)
target_compile_options(byedpi PRIVATE -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_libraries(byedpi PRIVATE android log dl)
# 16 KB pages (Android 15+ devices).
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon" "-Wl,-z,max-page-size=16384")
