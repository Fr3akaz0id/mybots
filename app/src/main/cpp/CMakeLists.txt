# Android build of the gadget core + JNI bridge.
# firmware/ is a symlink to ../../hermes-gadget-sdk/firmware; core sources compile untouched.
cmake_minimum_required(VERSION 3.22)
project(hgandroid CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Portable core, exactly as the desktop simulator builds it.
set(HG_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/firmware)
add_subdirectory(${HG_ROOT}/core ${CMAKE_CURRENT_BINARY_DIR}/core)

# Simulator-style library sources + JNI glue, packaged as libhgandroid.so.
add_library(hgandroid SHARED
  ${HG_ROOT}/sim/src/hgsim.cpp
  jni_bridge.cpp)
target_include_directories(hgandroid PRIVATE
  ${HG_ROOT}/sim/include
  ${HG_ROOT}/core/include)
target_link_libraries(hgandroid PRIVATE hg_core log)
