cmake_minimum_required(VERSION 3.22.1)
project(duiyi_llama)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# AGP debug native builds default to -O0. A 4B GGUF never finishes a prompt
# in time at -O0 on phone CPUs, so translation looks "stuck on source text".
string(APPEND CMAKE_C_FLAGS " -O3 -DNDEBUG")
string(APPEND CMAKE_CXX_FLAGS " -O3 -DNDEBUG")
string(APPEND CMAKE_C_FLAGS_DEBUG " -O3 -DNDEBUG")
string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O3 -DNDEBUG")

set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# Default to dotprod. Not for safety - the kernel reports HWCAP2_I8MM on every core of the
# target SoC, so the little cores do implement it - but because the wider 8x8 Q4_K kernel
# measured SLOWER here: generation is 8-31% slower with i8mm, most likely because this
# workload is almost pure GEMV (batch=1 decode) while the 8x8 interleave targets GEMM.
#
# The switch is kept because it makes that a one-flag, reproducible A/B:
#   ./gradlew assembleDebug -PduiyiArmArch=armv8.6-a+dotprod+i8mm
# See docs/10-latency-optimization.md section 5.2 for the numbers.
if(NOT GGML_CPU_ARM_ARCH)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()
message(STATUS "Duiyi ARM arch: ${GGML_CPU_ARM_ARCH}")

set(LLAMA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp")
if(EXISTS "${LLAMA_SRC}/CMakeLists.txt")
    add_subdirectory(${LLAMA_SRC} llama.cpp)
else()
    include(FetchContent)
    FetchContent_Declare(
        llama
        URL
            https://github.com/ggml-org/llama.cpp/archive/refs/tags/b10775.tar.gz
            https://mirror.ghproxy.com/https://github.com/ggml-org/llama.cpp/archive/refs/tags/b10775.tar.gz
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(duiyi_llama SHARED llama_jni.cpp)
find_library(log-lib log)
target_link_libraries(duiyi_llama PRIVATE llama android ${log-lib})
