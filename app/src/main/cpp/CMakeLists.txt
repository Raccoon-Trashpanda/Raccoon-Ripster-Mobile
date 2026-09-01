# Нативный аудиодвижок Ripster — Oboe (AAudio/OpenSL) + FLAC/WAV/ALAC.
# Цель — bit-perfect путь для локального lossless в обход ExoPlayer.
cmake_minimum_required(VERSION 3.22.1)
project(ripster_audio LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe приезжает через prefab из AAR com.google.oboe:oboe.
find_package(oboe REQUIRED CONFIG)

# Apple ALAC-декодер (Apache-2.0, vendored в alac/).
set(ALAC_SRC
    alac/ALACBitUtilities.c
    alac/EndianPortable.c
    alac/ag_dec.c
    alac/dp_dec.c
    alac/matrix_dec.c
    alac/ALACDecoder.cpp)

add_library(ripster_audio SHARED
    native_audio.cpp
    dr_impl.c
    ${ALAC_SRC})

target_include_directories(ripster_audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/alac)
target_compile_options(ripster_audio PRIVATE -O2 -fvisibility=hidden)
# ALAC-код старый — не воюем с его предупреждениями.
set_source_files_properties(${ALAC_SRC} PROPERTIES COMPILE_FLAGS "-w")

target_link_libraries(ripster_audio
    oboe::oboe
    mediandk      # AMediaExtractor — демукс m4a-контейнера
    android
    log)
