cmake_minimum_required(VERSION 3.22.1)

project(byedpi_native)

file(GLOB BYE_DPI_SRC byedpi/*.c)
list(REMOVE_ITEM BYE_DPI_SRC ${CMAKE_CURRENT_SOURCE_DIR}/byedpi/win_service.c)

add_library(byedpi SHARED ${BYE_DPI_SRC} native-lib.c utils.c)
target_include_directories(byedpi PRIVATE byedpi)

target_compile_options(byedpi PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)
target_link_options(byedpi PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")

target_link_libraries(byedpi PRIVATE android log)

# The MTProto/WebSocket core is shipped as a separate Rust shared library for
# the ARM ABIs used by physical Android phones. CI also builds an x86_64
# emulator; use a small unsupported-platform stub there so the rest of the UI
# and tests remain installable without pretending that Telegram proxying works
# on an ABI for which the Rust core is not shipped.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(tgwsproxy SHARED IMPORTED)
    set_target_properties(tgwsproxy PROPERTIES
            IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../tgwsLibs/${ANDROID_ABI}/libtgwsproxy.so")
else()
    add_library(tgwsproxy SHARED telegram-ws-stub.c)
    target_link_options(tgwsproxy PRIVATE
            "-Wl,-z,max-page-size=16384"
            "-Wl,-z,common-page-size=16384")
    target_link_libraries(tgwsproxy PRIVATE log)
endif()
add_library(tgwsbridge SHARED telegram-ws-jni.c)
target_link_options(tgwsbridge PRIVATE
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384")
target_link_libraries(tgwsbridge PRIVATE tgwsproxy log)
