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

# WavPack 5.7.0 (BSD-3, David Bryant) — только распаковка, без упаковки.
# Пункт трекера a4: «ALAC готов · APE/WavPack/DSD дальше».
#
# ENABLE_DSD включён намеренно: WavPack умеет нести DSD, и это первый путь,
# которым DSD вообще попадает в наш тракт. Отдельные .dsf/.dff — следующим
# шагом, у них свой контейнер.
set(WAVPACK_SRC
    wavpack/common_utils.c
    wavpack/decorr_utils.c
    wavpack/entropy_utils.c
    wavpack/open_utils.c
    wavpack/open_raw.c
    wavpack/read_words.c
    wavpack/tags.c
    wavpack/tag_utils.c
    wavpack/unpack.c
    wavpack/unpack_dsd.c
    wavpack/unpack_floats.c
    wavpack/unpack_seek.c
    wavpack/unpack_utils.c)

add_library(ripster_audio SHARED
    native_audio.cpp
    dr_impl.c
    ${ALAC_SRC}
    ${WAVPACK_SRC})

target_include_directories(ripster_audio PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/alac
    ${CMAKE_CURRENT_SOURCE_DIR}/wavpack)
target_compile_definitions(ripster_audio PRIVATE ENABLE_DSD=1 NO_TAGS=0)
target_compile_options(ripster_audio PRIVATE -O2 -fvisibility=hidden)
# ALAC-код старый — не воюем с его предупреждениями.
set_source_files_properties(${ALAC_SRC} PROPERTIES COMPILE_FLAGS "-w")
# Чужая библиотека — её предупреждения не наши; правя их, мы бы разошлись с
# апстримом и потеряли возможность обновляться.
set_source_files_properties(${WAVPACK_SRC} PROPERTIES COMPILE_FLAGS "-w")

target_link_libraries(ripster_audio
    oboe::oboe
    mediandk      # AMediaExtractor — демукс m4a-контейнера
    android
    log)
