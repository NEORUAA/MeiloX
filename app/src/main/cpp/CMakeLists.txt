cmake_minimum_required(VERSION 3.22.1)

project(meilox_native LANGUAGES C CXX ASM)

add_library(netease_log_policy SHARED netease_log_policy.c)

find_library(log_lib log)
target_link_libraries(netease_log_policy ${log_lib})
add_library(beatnet_native SHARED beatnet_native.cpp beatnet_weights.S jni_bridge.cpp)
set_property(SOURCE beatnet_weights.S PROPERTY OBJECT_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/beatnet_weights.bin)
target_compile_features(beatnet_native PRIVATE cxx_std_17)
target_compile_options(beatnet_native PRIVATE
    -O3 -ffast-math -fvisibility=hidden -fdata-sections -ffunction-sections)
target_include_directories(beatnet_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_options(beatnet_native PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
set_target_properties(beatnet_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN YES)
add_custom_command(TARGET beatnet_native POST_BUILD
    COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:beatnet_native>)
