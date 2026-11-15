#!/usr/bin/env python3
"""Apply the 71 missing-method patches to the FongMi/media checkout
(release-1.11.0-fongmi) so the TV app code compiles against it.
See stub-plan.md §4. Idempotent: re-running skips already-applied patches.
Usage: python3 patch_media.py [media_root]   (default: ../media)
Exits non-zero if any anchor is missing.
"""
import re
import sys
from pathlib import Path

MEDIA = Path(sys.argv[1] if len(sys.argv) > 1 else "../media").resolve()
FAILED = []


def read(rel: str) -> str:
    return (MEDIA / rel).read_text()


def write(rel: str, text: str) -> None:
    (MEDIA / rel).write_text(text)


def ensure_import(rel: str, import_line: str) -> None:
    """Add `import ...;` after the last import with the same package prefix,
    or after the last import overall. No-op if already present."""
    text = read(rel)
    if import_line in text:
        return
    pkg = import_line.split()[1].rsplit(".", 1)[0]  # e.g. androidx.media3.common.text
    imports = [m for m in re.finditer(r"^import .+;$", text, re.M)]
    anchor = None
    for m in imports:
        if m.group(0).startswith(f"import {pkg}."):
            anchor = m.group(0)
    if anchor is None:
        if imports:
            anchor = imports[-1].group(0)
        else:
            # No imports at all (e.g. a stub file): insert after the package line.
            m = re.search(r"^package .+;$", text, re.M)
            assert m, f"no package line in {rel}"
            anchor = m.group(0)
    text = text.replace(anchor, anchor + "\n" + import_line, 1)
    write(rel, text)
    print(f"  {rel}: + {import_line}")


def insert_after(rel: str, anchor: str, code: str, guard: str) -> None:
    """Insert code right after the first occurrence of anchor. Skips if guard
    already present; fails loudly if anchor is missing."""
    text = read(rel)
    if guard in text:
        print(f"  {rel}: skip (already has {guard.strip()[:40]})")
        return
    if anchor not in text:
        FAILED.append(f"{rel}: anchor not found: {anchor[:80]!r}")
        return
    text = text.replace(anchor, anchor + "\n" + code, 1)
    write(rel, text)
    print(f"  {rel}: + {guard.strip()[:60]}")


def insert_before(rel: str, anchor: str, code: str, guard: str) -> None:
    """Insert code right before the first occurrence of anchor. Skips if guard
    already present; fails loudly if anchor is missing."""
    text = read(rel)
    if guard in text:
        print(f"  {rel}: skip (already has {guard.strip()[:40]})")
        return
    if anchor not in text:
        FAILED.append(f"{rel}: anchor not found: {anchor[:80]!r}")
        return
    text = text.replace(anchor, code + "\n" + anchor, 1)
    write(rel, text)
    print(f"  {rel}: + {guard.strip()[:60]}")


def class_end_offset(text: str, class_decl_re: str) -> int:
    """Offset of the closing brace matching the class declaration."""
    m = re.search(class_decl_re, text, re.M)
    assert m, f"class decl not found: {class_decl_re}"
    i = text.index("{", m.end() - 1)
    depth = 0
    j = i
    n = len(text)
    while j < n:
        c = text[j]
        if c == "/" and j + 1 < n and text[j + 1] == "/":
            j = text.find("\n", j)
            if j == -1:
                break
            continue
        if c == "/" and j + 1 < n and text[j + 1] == "*":
            k = text.find("*/", j + 2)
            j = n if k == -1 else k + 2
            continue
        if c == '"':
            j += 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            j += 1
            continue
        if c == "'":
            j += 1
            while j < n and text[j] != "'":
                j += 2 if text[j] == "\\" else 1
            j += 1
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return j
        j += 1
    raise AssertionError("unbalanced braces")


def insert_before_class_end(rel: str, class_decl_re: str, code: str, guard: str) -> None:
    text = read(rel)
    if guard in text:
        print(f"  {rel}: skip (already has {guard.strip()[:40]})")
        return
    end = class_end_offset(text, class_decl_re)
    text = text[:end] + code + "\n" + text[end:]
    write(rel, text)
    print(f"  {rel}: + {guard.strip()[:60]} (before class end)")


P = "libraries/common/src/main/java/androidx/media3/common/Player.java"
PV = "libraries/ui/src/main/java/androidx/media3/ui/PlayerView.java"
MP = "libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/MpvPlayer.java"
DE = "libraries/extractor/src/main/java/androidx/media3/extractor/DefaultExtractorsFactory.java"

# ---------- 4.1 Player.java (4 methods) ----------
print("Player.java")
ensure_import(P, "import androidx.media3.common.text.SubtitleOffsets;")
insert_after(
    P,
    "  @UnstableApi\n"
    "  default long getTextOffsetMs() {\n"
    "    throw new UnsupportedOperationException();\n"
    "  }\n",
    "\n"
    "  default DiscMenuState getDiscMenuState() {\n"
    "    return DiscMenuState.EMPTY;\n"
    "  }\n"
    "\n"
    "  default SubtitleOffsets getSubtitleOffsets() {\n"
    "    return SubtitleOffsets.ZERO;\n"
    "  }\n"
    "\n"
    "  default void setSubtitleOffsets(SubtitleOffsets offsets) {}\n",
    "getDiscMenuState()",
)
insert_after(
    P,
    "    default void onPlaybackStateChanged(@State int playbackState) {}\n",
    "\n"
    "    default void onDiscMenuStateChanged(DiscMenuState state) {}\n",
    "onDiscMenuStateChanged(DiscMenuState state)",
)

# ---------- 4.2 PlayerView.java (7 methods + 4 fields) ----------
print("PlayerView.java")
ensure_import(PV, "import androidx.media3.common.DiscMenuController;")
ensure_import(PV, "import androidx.media3.common.VideoTransform;")
insert_after(
    PV,
    "  @Nullable private Player player;\n",
    "\n"
    "  private VideoTransform videoTransform = new VideoTransform(1.0f, 1.0f, 0.0f, 0.0f);\n"
    "  private float videoAspectRatioOverride;\n"
    "  @Nullable private DiscMenuController discMenuController;\n"
    "  @Nullable private Player backendPlayer;\n",
    "private VideoTransform videoTransform",
)
insert_before_class_end(
    PV,
    r"^public class PlayerView extends FrameLayout implements AdViewProvider \{$",
    "\n"
    "  public VideoTransform getVideoTransform() {\n"
    "    return videoTransform;\n"
    "  }\n"
    "\n"
    "  public void setVideoTransform(VideoTransform transform) {\n"
    "    this.videoTransform = transform;\n"
    "  }\n"
    "\n"
    "  public float getVideoAspectRatioOverride() {\n"
    "    return videoAspectRatioOverride;\n"
    "  }\n"
    "\n"
    "  public void setVideoAspectRatioOverride(float aspectRatio) {\n"
    "    this.videoAspectRatioOverride = aspectRatio;\n"
    "  }\n"
    "\n"
    "  public void setDiscMenuController(@Nullable DiscMenuController controller) {\n"
    "    this.discMenuController = controller;\n"
    "  }\n"
    "\n"
    "  public void setBackendPlayer(Player player) {\n"
    "    this.backendPlayer = player;\n"
    "  }\n"
    "\n"
    "  public boolean dispatchDiscMenuTouchEvent(MotionEvent event) {\n"
    "    return false;\n"
    "  }\n",
    "getVideoTransform()",
)

# ---------- 4.3 MpvPlayer.java (26 methods: 19 + 7 script methods, run #11) ----------
print("MpvPlayer.java")
text = read(MP)
if "implements DiscMenuController" not in text:
    old = "public final class MpvPlayer extends SimpleBasePlayer {"
    assert old in text, "MpvPlayer class decl anchor missing"
    write(MP, text.replace(old, "public final class MpvPlayer extends SimpleBasePlayer implements DiscMenuController {", 1))
    print("  MpvPlayer.java: class decl += implements DiscMenuController")
for imp in (
    "import androidx.media3.common.DiscMenuController;",
    "import androidx.media3.common.TrackSelectionOverride;",
    "import androidx.media3.common.text.SubtitleContent;",
    "import androidx.media3.common.text.SubtitleSelectionState;",
    "import androidx.media3.common.text.SubtitleOffsets;",
    "import androidx.media3.datasource.DataSource;",
    "import java.io.File;",
    "import java.util.function.Consumer;",
):
    ensure_import(MP, imp)
insert_before(
    MP,
    "  public static final class Builder {\n",
    "  @Override\n"
    "  public boolean open(String action, Consumer<DiscMenuController.OpenResult> callback) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  @Override\n"
    "  public boolean isActive() {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  @Override\n"
    "  public boolean isNavigationPlayback() {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  @Override\n"
    "  public boolean sendAction(String action) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  @Override\n"
    "  public boolean supportsPointer() {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public SubtitleSelectionState getSubtitleSelectionState() {\n"
    "    return SubtitleSelectionState.EMPTY;\n"
    "  }\n"
    "\n"
    "  public SubtitleContent getSubtitleContentForSelection(TrackSelectionOverride selection) {\n"
    "    return SubtitleContent.UNSUPPORTED;\n"
    "  }\n"
    "\n"
    "  public void setSubtitleContentEnabled(boolean enabled) {}\n"
    "\n"
    "  public boolean canRetrySubtitleContent(Format format) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public void retrySubtitleContent(Format format) {}\n"
    "\n"
    "  public void setSecondaryTextTrackSelectionOverride(\n"
    "      @Nullable TrackSelectionOverride selection) {}\n"
    "\n"
    "  public void setSecondaryTextTrackAutoSelectionEnabled(boolean enabled) {}\n"
    "\n"
    "  public void resetSecondaryTextTrackSelection() {}\n"
    "\n"
    "  public MpvDecoderMode getVideoDecoderMode() {\n"
    "    return MpvDecoderMode.AUTO;\n"
    "  }\n"
    "\n"
    "  public void setVideoDecoderMode(MpvDecoderMode mode) {}\n"
    "\n"
    "  public void setVerboseLoggingEnabled(boolean enabled) {}\n"
    "\n"
    "  public void setDiscDataSourceFactory(DataSource.Factory dataSourceFactory) {}\n"
    "\n"
    "  public void setSubtitleContentLoader(MpvSubtitleContentLoader loader) {}\n"
    "\n"
    "  // Script support (MpvScriptSession.java, run #11: 10x cannot find symbol).\n"
    "  // The author's private media has these; public FongMi/media 1.11.0-fongmi\n"
    "  // does not. Signatures are derived from the call sites; bodies are stubs\n"
    "  // (scripts just won't run until the real native implementation lands).\n"
    "  public interface LogListener {\n"
    "    void onLogMessage(String prefix, int level, String text);\n"
    "  }\n"
    "\n"
    "  public interface ScriptLoadListener {\n"
    "    void onLoaded(boolean success, long clientId);\n"
    "  }\n"
    "\n"
    "  public void setLogListener(int level, @Nullable LogListener listener) {}\n"
    "\n"
    "  public boolean setStartupScripts(List<File> scripts) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public boolean loadScript(String path, @Nullable ScriptLoadListener listener) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  @Nullable\n"
    "  public String getScriptData(String key) {\n"
    "    return null;\n"
    "  }\n"
    "\n"
    "  @Nullable\n"
    "  public String getInputBindings() {\n"
    "    return null;\n"
    "  }\n"
    "\n"
    "  public void unloadScript(long clientId) {}\n"
    "\n"
    "  public boolean runScriptBinding(String binding) {\n"
    "    return false;\n"
    "  }\n",
    "getSubtitleSelectionState()",
)
insert_after(
    MP,
    "    public MpvPlayer build() {\n"
    "      checkState(!buildCalled);\n"
    "      buildCalled = true;\n"
    "      return new MpvPlayer(context, config, decode, looper);\n"
    "    }\n",
    "\n"
    "    public Builder setVideoDecoderMode(MpvDecoderMode mode) {\n"
    "      return this;\n"
    "    }\n",
    "public Builder setVideoDecoderMode",
)

# ---------- 4.4 DefaultExtractorsFactory.java (1 method) ----------
print("DefaultExtractorsFactory.java")
insert_before_class_end(
    DE,
    r"^public final class DefaultExtractorsFactory implements ExtractorsFactory \{$",
    "\n"
    "  public synchronized DefaultExtractorsFactory setMp4ClearKeys(\n"
    "      Map<String, String> mp4ClearKeys) {\n"
    "    return this;\n"
    "  }\n",
    "setMp4ClearKeys(",
)

# New path constants for the run #12 round (§4.5-§4.17).
SV = "libraries/ui/src/main/java/androidx/media3/ui/SubtitleView.java"
PSV = "libraries/ui/src/main/java/androidx/media3/ui/PlayerSeekView.java"
MPC = "libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/MpvPlayerConfig.java"
MSO = "libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/MpvSubtitleOptions.java"
ML = "libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/MpvLibrary.java"
MX = "libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java"
MAF = "libraries/mpvplayer/src/main/java/androidx/media3/mpvplayer/audio/MpvAudioFilter.java"
PML = "libraries/exoplayer/src/main/java/androidx/media3/exoplayer/source/preload/PreloadManagerListener.java"
VT = "libraries/common/src/main/java/androidx/media3/common/VideoTransform.java"
SSS = "libraries/common/src/main/java/androidx/media3/common/text/SubtitleSelectionState.java"
STS = "libraries/mpvplayer/src/main/java/androidx/media3/exoplayer/text/SubtitleTranscriptSession.java"

# ---------- 4.5 Player.java (EVENT_DISC_MENU_STATE_CHANGED constant, run #12) ----------
# PlaybackActivity.java:1093 uses it in events.containsAny(...). Next free value
# after EVENT_MEDIA_EDITIONS_CHANGED = 32.
print("Player.java (4.5)")
insert_after(
    P,
    "  /** {@link #getCurrentMediaEditions()} changed. */\n"
    "  @UnstableApi int EVENT_MEDIA_EDITIONS_CHANGED = 32;\n",
    "\n"
    "  /** {@link #getDiscMenuState()} changed. */\n"
    "  @UnstableApi int EVENT_DISC_MENU_STATE_CHANGED = 33;\n",
    "EVENT_DISC_MENU_STATE_CHANGED = 33",
)

# ---------- 4.6 PlayerView.java (video-transform methods, run #12) ----------
# TouchInput.java (mobile flavor): animateVideoTransform(transform, 250/180),
# finishVideoTransformAnimation(), getVideoBounds() -> RectF (pinchContentBounds.set(...)).
# getVideoBounds returns an empty RectF so the caller's isEmpty() fallback applies.
print("PlayerView.java (4.6)")
ensure_import(PV, "import android.graphics.RectF;")
insert_before_class_end(
    PV,
    r"^public class PlayerView extends FrameLayout implements AdViewProvider \{$",
    "\n"
    "  public void animateVideoTransform(VideoTransform transform, int durationMs) {}\n"
    "\n"
    "  public void finishVideoTransformAnimation() {}\n"
    "\n"
    "  public RectF getVideoBounds() {\n"
    "    return new RectF();\n"
    "  }\n",
    "animateVideoTransform(VideoTransform",
)

# ---------- 4.7 SubtitleView.java (setBitmapSubtitleBrightness, run #12) ----------
# SubtitleSetting.java:329 passes getBitmapBrightness() (float).
print("SubtitleView.java")
insert_before_class_end(
    SV,
    r"^public final class SubtitleView extends FrameLayout \{$",
    "\n"
    "  public void setBitmapSubtitleBrightness(float brightness) {}\n",
    "setBitmapSubtitleBrightness(",
)

# ---------- 4.8 PlayerSeekView.java (setDurationSupplier, run #12) ----------
# PlaybackActivity.java:1084 passes this::getActivePlaybackDuration (returns long).
# LongSupplier chosen; Supplier<Long> would also compile the call site.
print("PlayerSeekView.java")
ensure_import(PSV, "import java.util.function.LongSupplier;")
insert_after(
    PSV,
    "  @Nullable private Player player;\n",
    "\n"
    "  @Nullable private LongSupplier durationSupplier;\n",
    "private LongSupplier durationSupplier",
)
insert_before(
    PSV,
    "  /** Returns the bound {@link TimeBar}. */\n",
    "  /** Sets a supplier for the duration to display instead of the player's duration. */\n"
    "  public void setDurationSupplier(@Nullable LongSupplier durationSupplier) {\n"
    "    this.durationSupplier = durationSupplier;\n"
    "  }\n",
    "setDurationSupplier(",
)

# ---------- 4.9 MpvPlayer.java (motion interpolation + audio filter listener, run #12) ----------
# MpvMotionSettingPanel.java:42/49/59/60/85/86/89; MpvPlayerEffect.java:29.
# setMotionInterpolationFps MUST return ListenableFuture<Void>: the panel does
# Futures.addCallback(..., new FutureCallback<>() { onSuccess(@Nullable Void)... });
# only ListenableFuture<Void> lets the diamond infer V=Void, which also makes the
# two @Override errors at MpvMotionSettingPanel.java:60-61 disappear (they are
# knock-on errors of the missing method, not separate missing methods).
# Futures/ListenableFuture are already imported in MpvPlayer.java.
print("MpvPlayer.java (4.9)")
insert_before(
    MP,
    "  public static final class Builder {\n",
    "  public void addMotionInterpolationListener(Runnable listener) {}\n"
    "\n"
    "  public void removeMotionInterpolationListener(Runnable listener) {}\n"
    "\n"
    "  public boolean isMotionInterpolationSupported() {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public boolean isMotionInterpolationUpdatePending() {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public ListenableFuture<Void> setMotionInterpolationFps(int fps) {\n"
    "    return Futures.immediateFuture(null);\n"
    "  }\n"
    "\n"
    "  public int getMotionInterpolationFps() {\n"
    "    return 0;\n"
    "  }\n"
    "\n"
    "  public float getMotionInterpolationEffectiveTargetFps() {\n"
    "    return 0;\n"
    "  }\n"
    "\n"
    "  public interface AudioFilterListener {\n"
    "    void onAudioFilterApplied(boolean success);\n"
    "  }\n"
    "\n"
    "  public void setAudioFilterListener(@Nullable AudioFilterListener listener) {}\n",
    "addMotionInterpolationListener(",
)

# ---------- 4.10 MpvPlayerConfig.java (Builder setters, run #12) ----------
# MpvUtil.java: buildConfig passes OkHttpDataSource.Factory + two Files;
# addMotionOptions passes an int. setDefaultFontDirectories was NOT in the run #12
# error list but is verified missing from public media (0 hits in the whole tree)
# and MpvUtil calls it, so it is patched too. Bodies are no-ops: the values are
# not wired into MpvPlayerConfig (inert; real wiring needs field plumbing).
print("MpvPlayerConfig.java")
ensure_import(MPC, "import androidx.media3.datasource.DataSource;")
insert_before(
    MPC,
    "    public MpvPlayerConfig build() {\n",
    "    @CanIgnoreReturnValue\n"
    "    public Builder setDataSourceFactory(DataSource.Factory dataSourceFactory) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setMotionInterpolationFps(int fps) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setDefaultFontDirectories(File fontsDirectory, File fallbackFontsDirectory) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n",
    "setDataSourceFactory(DataSource.Factory",
)

# ---------- 4.11 MpvSubtitleOptions.java (Builder setters, run #12) ----------
# MpvUtil.java: buildSubtitleOptions. The error list lumped these under
# "MpvPlayerConfig.Builder" but the call sites are on MpvSubtitleOptions.Builder.
# setVerticalOffset takes double (call passes float, widens); setDefaultStyle
# takes (int, int, int, int, float, float) per the error list / CaptionStyleCompat.
print("MpvSubtitleOptions.java")
insert_before(
    MSO,
    "    public MpvSubtitleOptions build() {\n",
    "    @CanIgnoreReturnValue\n"
    "    public Builder setFontsDirectory(String fontsDirectory) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setVerticalOffset(double verticalOffset) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setBitmapSubtitleBrightness(float brightness) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setSecondarySubtitlePosition(double position) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setSecondaryVerticalOffset(double offset) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setSecondaryAssStyleOverride(boolean override) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setFontFamily(String fontFamily) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n"
    "\n"
    "    @CanIgnoreReturnValue\n"
    "    public Builder setDefaultStyle(\n"
    "        int foregroundColor,\n"
    "        int backgroundColor,\n"
    "        int edgeType,\n"
    "        int edgeColor,\n"
    "        float edgeWidth,\n"
    "        float shadowOffset) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n",
    "setFontsDirectory(",
)

# ---------- 4.12 MpvLibrary.java (setLibraryLoader, run #12) ----------
# App.java:105 passes MpvUtil::loadRuntime (no args, returns boolean).
# BooleanSupplier chosen; Supplier<Boolean>/Runnable would also compile.
# LibraryLoader is an abstract class, not a functional interface, so the
# parameter must be a SAM type. The loader is stored but never invoked (inert).
print("MpvLibrary.java")
ensure_import(ML, "import java.util.function.BooleanSupplier;")
insert_after(
    ML,
    "  /** Returns whether the underlying native libraries are available, loading them if necessary. */\n"
    "  public static boolean isAvailable() {\n"
    "    return LOADER.isAvailable();\n"
    "  }\n",
    "\n"
    "  private static BooleanSupplier libraryLoader;\n"
    "\n"
    "  /** Sets a loader for the native libraries (the app loads a bundled runtime). */\n"
    "  public static void setLibraryLoader(BooleanSupplier loader) {\n"
    "    libraryLoader = loader;\n"
    "  }\n",
    "setLibraryLoader(",
)

# ---------- 4.13 MatroskaExtractor.java (FLAG_DEFER_SEEK_FOR_CUES, run #12) ----------
# ExoMediaSourceFactory.java passes it to setMatroskaExtractorFlags(...).
# Value 1 << 2 is the next free bit (existing flags are 1 and 1 << 1); inert.
print("MatroskaExtractor.java")
insert_after(
    MX,
    "  public static final int FLAG_EMIT_RAW_SUBTITLE_DATA = 1 << 1; // 2\n",
    "\n"
    "  /** Flag to defer seeking for cues until the cues element is needed. */\n"
    "  public static final int FLAG_DEFER_SEEK_FOR_CUES = 1 << 2; // 4\n",
    "FLAG_DEFER_SEEK_FOR_CUES = 1 << 2",
)
# Add the new flag to the @IntDef value list (source-retention, lint only).
_mx_text = read(MX)
_mx_old = (
    "  @IntDef(\n"
    "      flag = true,\n"
    "      value = {FLAG_DISABLE_SEEK_FOR_CUES, FLAG_EMIT_RAW_SUBTITLE_DATA})\n"
    "  public @interface Flags {}\n"
)
_mx_new = (
    "  @IntDef(\n"
    "      flag = true,\n"
    "      value = {\n"
    "        FLAG_DISABLE_SEEK_FOR_CUES,\n"
    "        FLAG_EMIT_RAW_SUBTITLE_DATA,\n"
    "        FLAG_DEFER_SEEK_FOR_CUES\n"
    "      })\n"
    "  public @interface Flags {}\n"
)
if _mx_new in _mx_text:
    print("  MatroskaExtractor.java: skip (@IntDef already updated)")
elif _mx_old in _mx_text:
    write(MX, _mx_text.replace(_mx_old, _mx_new, 1))
    print("  MatroskaExtractor.java: + FLAG_DEFER_SEEK_FOR_CUES in @IntDef")
else:
    FAILED.append("MatroskaExtractor.java: @IntDef anchor not found")

# ---------- 4.14 DefaultExtractorsFactory.java (setMatroskaExtractorFlagsForHttpSources, run #12) ----------
# ExoMediaSourceFactory.java:165 chains it after setTsExtractorTimestampSearchBytes.
# Also NOT in the run #12 error list but verified missing (0 hits in the whole
# media tree), so ExoMediaSourceFactory likely had 2 errors, not 1.
print("DefaultExtractorsFactory.java (4.14)")
insert_after(
    DE,
    "  private @MatroskaExtractor.Flags int matroskaFlags;\n",
    "\n"
    "  private @MatroskaExtractor.Flags int matroskaFlagsForHttpSources;\n",
    "matroskaFlagsForHttpSources",
)
insert_after(
    DE,
    "  @CanIgnoreReturnValue\n"
    "  public synchronized DefaultExtractorsFactory setMatroskaExtractorFlags(\n"
    "      @MatroskaExtractor.Flags int flags) {\n"
    "    this.matroskaFlags = flags;\n"
    "    return this;\n"
    "  }\n",
    "\n"
    "  /**\n"
    "   * Sets flags for {@link MatroskaExtractor} instances created for HTTP sources.\n"
    "   *\n"
    "   * @param flags The flags to use.\n"
    "   * @return The factory, for convenience.\n"
    "   */\n"
    "  @CanIgnoreReturnValue\n"
    "  public synchronized DefaultExtractorsFactory setMatroskaExtractorFlagsForHttpSources(\n"
    "      @MatroskaExtractor.Flags int flags) {\n"
    "    this.matroskaFlagsForHttpSources = flags;\n"
    "    return this;\n"
    "  }\n",
    "setMatroskaExtractorFlagsForHttpSources(",
)

# ---------- 4.15 MpvAudioFilter.java (Builder.addDynamicNormalization, run #12) ----------
# MpvAudioEffectFilter.java:48 calls builder.addDynamicNormalization("loudness", 0.125, 4.0).
# The two doubles are (threshold, ratio) by best guess; names do not affect compilation.
print("MpvAudioFilter.java")
insert_before(
    MAF,
    "    public MpvAudioFilter build() {\n",
    "    @CanIgnoreReturnValue\n"
    "    public Builder addDynamicNormalization(String id, double threshold, double ratio) {\n"
    "      checkState(!buildCalled);\n"
    "      return this;\n"
    "    }\n",
    "addDynamicNormalization(",
)

# ---------- 4.16 PreloadManagerListener.java (onPreloadPaused default, run #12) ----------
# ExoPlayerSession.java overrides onCompleted + onPreloadPaused + onError on a
# PreloadManagerListener; the real interface has only onCompleted + onError, so
# the @Override on onPreloadPaused failed. Adding the default method fixes it.
print("PreloadManagerListener.java")
insert_after(
    PML,
    "  /** Called when an {@linkplain PreloadException error} occurs. */\n"
    "  default void onError(PreloadException exception) {}\n",
    "\n"
    "  /** Called when preloading is paused for the given {@link MediaItem}. */\n"
    "  default void onPreloadPaused(MediaItem mediaItem) {}\n",
    "onPreloadPaused(",
)

# ---------- 4.17 Stub-file patches (run #12; applied to the unzipped stub files) ----------
# The workflow unzips stubs-media.zip into ../media BEFORE running this script,
# so these paths exist in the checkout. The zips themselves do NOT change.
print("VideoTransform.java (stub)")
ensure_import(VT, "import java.util.Objects;")
insert_after(
    VT,
    "  public static final float MAX_SCALE = 8.0f; // 值未验证：用法只要求能做上限校验，取约定值\n",
    "\n"
    "  public static final VideoTransform DEFAULT = new VideoTransform(1.0f, 1.0f, 0.0f, 0.0f);\n",
    "DEFAULT = new VideoTransform",
)
insert_before_class_end(
    VT,
    r"^public final class VideoTransform \{$",
    "\n"
    "  @Override\n"
    "  public boolean equals(Object obj) {\n"
    "    if (this == obj) return true;\n"
    "    if (!(obj instanceof VideoTransform)) return false;\n"
    "    VideoTransform other = (VideoTransform) obj;\n"
    "    return scaleX == other.scaleX\n"
    "        && scaleY == other.scaleY\n"
    "        && panX == other.panX\n"
    "        && panY == other.panY;\n"
    "  }\n"
    "\n"
    "  @Override\n"
    "  public int hashCode() {\n"
    "    return Objects.hash(scaleX, scaleY, panX, panY);\n"
    "  }\n",
    "public boolean equals(Object obj)",
)
print("SubtitleSelectionState.java (stub)")
ensure_import(SSS, "import java.util.Collections;")
ensure_import(SSS, "import java.util.List;")
insert_after(
    SSS,
    "  @Nullable public final TrackSelectionOverride activeSecondarySelection;\n",
    "\n"
    "  public final List<TrackSelectionOverride> secondaryCandidates = Collections.emptyList();\n"
    "  @Nullable public final TrackSelectionOverride requestedSecondarySelection = null;\n"
    "  public final boolean secondaryPromotedToPrimary = false;\n",
    "secondaryCandidates",
)
print("SubtitleTranscriptSession.java (stub)")
insert_before_class_end(
    STS,
    r"^public final class SubtitleTranscriptSession \{$",
    "\n"
    "  public void setContentEnabled(boolean enabled) {}\n"
    "\n"
    "  public boolean canRetryContent(Format format) {\n"
    "    return false;\n"
    "  }\n"
    "\n"
    "  public void retryContent(Format format) {}\n",
    "setContentEnabled(",
)

if FAILED:
    print("FAILED anchors:")
    for f in FAILED:
        print(" -", f)
    sys.exit(1)
print("All media patches applied.")
