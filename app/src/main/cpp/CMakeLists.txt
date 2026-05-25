cmake_minimum_required(VERSION 3.22.1)
project(cleancad LANGUAGES C)

add_library(dwgjni SHARED dwgjni.c)

find_library(log-lib log)
target_link_libraries(dwgjni ${log-lib})
