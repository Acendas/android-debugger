cmake_minimum_required(VERSION 3.22.1)
project(amdb_agent LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Version + protocol-version are stamped into the binary at build time.
# Kotlin side asserts an exact match on `attach-agent`'s options string.
if(NOT DEFINED AGENT_VERSION_STR)
    set(AGENT_VERSION_STR "1.4.0")
endif()
if(NOT DEFINED AGENT_PROTOCOL_VERSION_INT)
    set(AGENT_PROTOCOL_VERSION_INT 1)
endif()

add_library(amdb_agent SHARED src/agent.cpp)

# `jvmti.h` is vendored from OpenJDK 11 (GPLv2 + Classpath Exception). The NDK
# doesn't ship it because JVMTI is a JVM-side header, not an OS one. Standard
# practice for JVMTI agents on Android (Studio's Apply Changes does the same).
# See agent/include/jvmti.h header for the upstream license terms.
target_include_directories(amdb_agent PRIVATE "${CMAKE_SOURCE_DIR}/include")

target_compile_definitions(amdb_agent PRIVATE
    AGENT_VERSION="${AGENT_VERSION_STR}"
    AGENT_PROTOCOL_VERSION=${AGENT_PROTOCOL_VERSION_INT}
)

# Per D12 — fully stripped release builds. Linker drops the symbol table
# entirely; the resulting .so is ~600 KB per ABI. Crash diagnostics live in
# the addresses-only crash-marker file (D16).
target_compile_options(amdb_agent PRIVATE
    -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden -fdata-sections -ffunction-sections
    $<$<CONFIG:Release>:-Os>
)
target_link_options(amdb_agent PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    $<$<CONFIG:Release>:-Wl,--strip-all>
)

# JVMTI lives in libdl/libc; logging via liblog. Android NDK gives us liblog.
find_library(LOG_LIB log)
target_link_libraries(amdb_agent PRIVATE ${LOG_LIB})
