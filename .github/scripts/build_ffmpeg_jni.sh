#!/bin/bash -e
# Compile androidx/media's ffmpeg_jni.cc into libffmpegJNI.so linking the
# FongMi unified libffmpeg.so, for armeabi-v7a and arm64-v8a.
#
# Args:
#   $1  NDK root (contains toolchains/llvm/prebuilt/<host>/bin)
#   $2  ffmpeg_jni.cc source path
#   $3  mpv-android buildscripts/prefix root (contains armv7l/, arm64/,
#       each with include/ and lib/)
#   $4  output directory (per-ABI libffmpegJNI.so written here)

NDK="$1"
JNI_SRC="$2"
PREFIX_ROOT="$3"
OUT_DIR="$4"

HOST="linux-x86_64"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST/bin"

mkdir -p "$OUT_DIR"

build_abi() {
  local abi="$1" prefix_name="$2" cc="$3"
  local include="$PREFIX_ROOT/$prefix_name/include"
  local libdir="$PREFIX_ROOT/$prefix_name/lib"
  local out="$OUT_DIR/$abi"
  mkdir -p "$out"
  if [ ! -f "$include/libavcodec/avcodec.h" ]; then
    echo "missing FFmpeg headers at $include" >&2
    exit 1
  fi
  if [ ! -f "$libdir/libffmpeg.so" ]; then
    echo "missing libffmpeg.so at $libdir" >&2
    exit 1
  fi
  "$TOOLCHAIN/$cc" -shared -fPIC -O2 -std=c++11 \
    -I"$include" \
    "$JNI_SRC" \
    -L"$libdir" -lffmpeg -llog \
    -Wl,-Bsymbolic \
    -o "$out/libffmpegJNI.so"
  echo "built $out/libffmpegJNI.so"
}

build_abi armeabi-v7a armv7l "armv7a-linux-androideabi24-clang"
build_abi arm64-v8a arm64 "aarch64-linux-android24-clang"
