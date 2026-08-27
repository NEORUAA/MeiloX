cmake_minimum_required(VERSION 3.22.1)

project(netease_log_policy C)

add_library(netease_log_policy SHARED netease_log_policy.c)

find_library(log_lib log)
target_link_libraries(netease_log_policy ${log_lib})
