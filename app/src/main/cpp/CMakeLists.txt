cmake_minimum_required(VERSION 3.22.1)
project(cleancad LANGUAGES C)

# Workaround (Windows): Git's system core.autocrlf=true checks out LibreDWG's
# configure.ac with CRLF endings. Its get-so-version.pl then leaves a stray CR in
# LIBREDWG_SO_VERSION, which corrupts the generated config.h and breaks the build.
# Strip carriage returns from configure.ac before LibreDWG's CMake reads it.
# (perl is already required by LibreDWG's own build.)
execute_process(
        COMMAND perl -i -pe "s/\\r//g"
                "${CMAKE_CURRENT_SOURCE_DIR}/libredwg/configure.ac")

# LibreDWG: build only the static library (no programs, tests, LTO, or -Werror).
set(LIBREDWG_LIBONLY ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(ENABLE_LTO OFF CACHE BOOL "" FORCE)
set(DISABLE_WERROR ON CACHE BOOL "" FORCE)
add_subdirectory(libredwg)

add_library(dwgjni SHARED dwgjni.c)

target_include_directories(dwgjni PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/libredwg/include)

find_library(log-lib log)
# LibreDWG's CMake library target is named "redwg" on non-Windows platforms.
target_link_libraries(dwgjni redwg ${log-lib})
