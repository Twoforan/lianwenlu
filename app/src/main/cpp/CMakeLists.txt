cmake_minimum_required(VERSION 3.22.1)
project(lwq_embed C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 以子目录方式引入仓库根目录下的 llama.cpp：只产出静态库 llama（CPU 后端）。
# LLAMA_STANDALONE 为 OFF 时，BUILD_COMMON/TESTS/TOOLS/EXAMPLES/SERVER/APP 默认都是 OFF。
set(LLAMA_STANDALONE     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP      OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL        OFF CACHE BOOL "" FORCE)
# 交叉编译到 Android：关闭本机 CPU 指令探测与 OpenMP，避免编译出宿主机不支持的指令
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp
    ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp-build)

add_library(lwq_embed SHARED lwq_embed.cpp)
target_include_directories(lwq_embed PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp/ggml/include)
target_link_libraries(lwq_embed llama android log ${CMAKE_DL_LIBS})
