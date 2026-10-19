# Modelo de lenguaje local (llama.cpp) para Monki: último respaldo cuando las APIs gratis se limitan o no hay internet.
cmake_minimum_required(VERSION 3.22)
project(monki_llm C CXX)

set(CMAKE_CXX_STANDARD 17)

# Todo estático dentro de libmonki_llm.so; solo lo necesario para correr un modelo
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# Cortex-A55 de la RK3566: producto punto y fp16
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

add_subdirectory(llama.cpp build-llama)

add_library(monki_llm SHARED monki_llm.cpp)
target_link_libraries(monki_llm llama log)
