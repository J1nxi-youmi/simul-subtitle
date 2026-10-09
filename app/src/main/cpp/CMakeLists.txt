cmake_minimum_required(VERSION 3.22.1)
project(jpsub C CXX)
set(CMAKE_CXX_STANDARD 17)

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(jpsub SHARED translator_jni.cpp)
target_link_libraries(jpsub llama)
