#!/usr/bin/env python3
"""Package mpv/ffmpeg native shared libraries into FongMi-style AARs.

Inputs:
  --libs-dir   mpv-android build output: app/src/main/libs (per-ABI *.so)
  --jni-dir    libffmpegJNI.so build output (per-ABI libffmpegJNI.so)
  --out-dir    destination (TVBoxOSC/app/libs)

Produces:
  lib-mpvplayer-release.aar      jni/{abi}/{libmpv.so,libplayer.so,libc++_shared.so}
  lib-decoder-ffmpeg-release.aar jni/{abi}/{libffmpeg.so,libffmpegJNI.so,libc++_shared.so[,libffmpegDoviJNI.so]}
"""
import argparse
import os
import sys
import zipfile

ABIS = ("armeabi-v7a", "arm64-v8a")


def build_manifest(package):
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
        'package="%s"></manifest>\n' % package
    )


def empty_jar():
    import io
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED):
        pass
    return buf.getvalue()


def require(path):
    if not os.path.isfile(path):
        raise SystemExit("missing native library: %s" % path)


def package_aar(out_path, package, entries):
    """entries: {archive_path: source_path}"""
    with zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("AndroidManifest.xml", build_manifest(package))
        zf.writestr("classes.jar", empty_jar())
        zf.writestr("R.txt", "")
        for arc, src in entries.items():
            require(src)
            zf.write(src, arc)
    print("wrote %s (%d entries)" % (out_path, len(entries) + 3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--libs-dir", required=True)
    parser.add_argument("--jni-dir", required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()

    if not os.path.isdir(args.out_dir):
        os.makedirs(args.out_dir)

    mpv_entries = {}
    ffmpeg_entries = {}
    for abi in ABIS:
        libs_abi = os.path.join(args.libs_dir, abi)
        jni_abi = os.path.join(args.jni_dir, abi)
        for name in ("libmpv.so", "libplayer.so", "libc++_shared.so"):
            mpv_entries["jni/%s/%s" % (abi, name)] = os.path.join(libs_abi, name)
        for name in ("libffmpeg.so", "libc++_shared.so"):
            ffmpeg_entries["jni/%s/%s" % (abi, name)] = os.path.join(libs_abi, name)
        # libffmpegJNI.so is mandatory: always register so require() enforces it
        ffmpeg_entries["jni/%s/libffmpegJNI.so" % abi] = os.path.join(jni_abi, "libffmpegJNI.so")
        dovi = os.path.join(jni_abi, "libffmpegDoviJNI.so")
        if os.path.isfile(dovi):
            ffmpeg_entries["jni/%s/libffmpegDoviJNI.so" % abi] = dovi

    package_aar(os.path.join(args.out_dir, "lib-mpvplayer-release.aar"),
                "com.fongmi.mpv", mpv_entries)
    package_aar(os.path.join(args.out_dir, "lib-decoder-ffmpeg-release.aar"),
                "com.fongmi.ffmpeg", ffmpeg_entries)


if __name__ == "__main__":
    main()
