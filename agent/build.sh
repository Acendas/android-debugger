#!/usr/bin/env bash
# Build the JVMTI agent for every shipped ABI and stage the .so files into
# `dist/agents/<abi>/`. Invoked from the Gradle `assembleAgent` task, or
# directly by maintainers.
#
# Requires:
#   - $ANDROID_NDK_HOME pointing at NDK r26+ (or set via $ANDROID_HOME/ndk/<ver>)
#   - CMake 3.22+ (the NDK SDK ships one at $ANDROID_HOME/cmake/<ver>/bin/cmake)
#
# Usage:
#   ./agent/build.sh           # release build, all ABIs
#   ./agent/build.sh debug     # debug variant (keeps symbols, ANDROID_LOG_DEBUG)

set -euo pipefail

AGENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_ROOT="$(cd "${AGENT_DIR}/.." && pwd)"
BUILD_TYPE="${1:-Release}"
case "${BUILD_TYPE,,}" in
  release|relwithdebinfo) BUILD_TYPE="Release" ;;
  debug) BUILD_TYPE="Debug" ;;
  *) echo "Unknown build type: ${BUILD_TYPE} (expected 'release' or 'debug')" >&2; exit 1 ;;
esac

# Resolve NDK.
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
  if [[ -d "${ANDROID_HOME:-}/ndk" ]]; then
    # Pick the highest version under $ANDROID_HOME/ndk/.
    ANDROID_NDK_HOME="$(ls -1 "${ANDROID_HOME}/ndk" | sort -V | tail -1)"
    ANDROID_NDK_HOME="${ANDROID_HOME}/ndk/${ANDROID_NDK_HOME}"
  fi
fi
if [[ ! -d "${ANDROID_NDK_HOME:-}/build/cmake" ]]; then
  echo "ANDROID_NDK_HOME not set or invalid (looked for build/cmake/android.toolchain.cmake)" >&2
  echo "  Install via: sdkmanager 'ndk;27.2.12479018'" >&2
  echo "  Or set ANDROID_NDK_HOME explicitly." >&2
  exit 1
fi

# Resolve CMake. Prefer host cmake; fall back to the SDK-bundled cmake.
CMAKE="$(command -v cmake || true)"
if [[ -z "${CMAKE}" ]]; then
  if [[ -d "${ANDROID_HOME:-}/cmake" ]]; then
    CMAKE_DIR="$(ls -1 "${ANDROID_HOME}/cmake" | sort -V | tail -1)"
    CMAKE="${ANDROID_HOME}/cmake/${CMAKE_DIR}/bin/cmake"
    if [[ ! -x "${CMAKE}" ]]; then
      echo "No cmake found on PATH; SDK cmake at ${CMAKE} is not executable" >&2
      exit 1
    fi
  else
    echo "No cmake found (PATH or \$ANDROID_HOME/cmake)" >&2
    exit 1
  fi
fi

# Read the agent version from CMakeLists.txt (default-set there) so the build
# script doesn't carry its own copy.
AGENT_VERSION="$(grep -E '^\s*set\(AGENT_VERSION_STR' "${AGENT_DIR}/CMakeLists.txt" \
  | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
AGENT_PROTOCOL_VERSION="$(grep -E '^\s*set\(AGENT_PROTOCOL_VERSION_INT' "${AGENT_DIR}/CMakeLists.txt" \
  | head -1 | sed -E 's/.*([0-9]+)\).*/\1/')"

ABIS=( "arm64-v8a" "x86_64" "armeabi-v7a" )

echo "==> agent build"
echo "    plugin root:    ${PLUGIN_ROOT}"
echo "    NDK:            ${ANDROID_NDK_HOME}"
echo "    cmake:          ${CMAKE}"
echo "    build type:     ${BUILD_TYPE}"
echo "    agent version:  ${AGENT_VERSION} (protocol v${AGENT_PROTOCOL_VERSION})"
echo "    ABIs:           ${ABIS[*]}"

mkdir -p "${PLUGIN_ROOT}/dist/agents"

for abi in "${ABIS[@]}"; do
  echo ""
  echo "==> building ${abi}"
  BUILD_DIR="${AGENT_DIR}/build/${abi}"
  rm -rf "${BUILD_DIR}"
  mkdir -p "${BUILD_DIR}"

  "${CMAKE}" \
    -S "${AGENT_DIR}" \
    -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="${abi}" \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
    -DAGENT_VERSION_STR="${AGENT_VERSION}" \
    -DAGENT_PROTOCOL_VERSION_INT="${AGENT_PROTOCOL_VERSION}" \
    >/dev/null

  "${CMAKE}" --build "${BUILD_DIR}" --config "${BUILD_TYPE}" -j

  mkdir -p "${PLUGIN_ROOT}/dist/agents/${abi}"
  cp "${BUILD_DIR}/libamdb_agent.so" "${PLUGIN_ROOT}/dist/agents/${abi}/libamdb_agent.so"
  size_bytes="$(stat -f%z "${PLUGIN_ROOT}/dist/agents/${abi}/libamdb_agent.so" 2>/dev/null \
    || stat -c%s "${PLUGIN_ROOT}/dist/agents/${abi}/libamdb_agent.so" 2>/dev/null \
    || echo "?")"
  echo "    -> dist/agents/${abi}/libamdb_agent.so (${size_bytes} bytes)"
done

echo ""
echo "==> agent build complete"
