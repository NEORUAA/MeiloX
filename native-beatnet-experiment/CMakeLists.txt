cmake_minimum_required(VERSION 3.22)
project(beatnet_native LANGUAGES CXX ASM)

set(BEATNET_SOURCES beatnet_native.cpp beatnet_weights.S)
set_property(SOURCE beatnet_weights.S PROPERTY OBJECT_DEPENDS
    ${CMAKE_CURRENT_SOURCE_DIR}/beatnet_weights.bin)
if(ANDROID)
  list(APPEND BEATNET_SOURCES jni_bridge.cpp)
endif()
if(ANDROID)
  add_library(beatnet_native SHARED ${BEATNET_SOURCES})
else()
  add_library(beatnet_native STATIC ${BEATNET_SOURCES})
endif()
target_compile_features(beatnet_native PRIVATE cxx_std_17)
target_compile_options(beatnet_native PRIVATE
    -O3 -ffast-math -fvisibility=hidden -fdata-sections -ffunction-sections)
target_include_directories(beatnet_native PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_options(beatnet_native PRIVATE
    -Wl,--gc-sections -Wl,--exclude-libs,ALL)
set_target_properties(beatnet_native PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES)
if(ANDROID)
  add_custom_command(TARGET beatnet_native POST_BUILD
      COMMAND ${CMAKE_STRIP} --strip-unneeded $<TARGET_FILE:beatnet_native>)
else()
  add_executable(beatnet_cli test_cli.cpp)
  target_compile_features(beatnet_cli PRIVATE cxx_std_17)
  target_link_libraries(beatnet_cli PRIVATE beatnet_native)
endif()
