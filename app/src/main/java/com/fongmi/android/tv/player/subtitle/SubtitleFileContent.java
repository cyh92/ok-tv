package com.fongmi.android.tv.player.subtitle;

import android.net.Uri;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.text.SubtitleContent;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.text.DefaultSubtitleContentLoader;
// 旧版 mpv AAR 无 MpvSubtitleContentLoader，相关引用已注释
//import androidx.media3.mpvplayer.MpvSubtitleContentLoader;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.UriUtil;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Set;

// 旧版 mpv AAR 无 MpvSubtitleContentLoader，接口实现已注释
//public final class SubtitleFileContent implements MpvSubtitleContentLoader {
public final class SubtitleFileContent  {

    private static final Set<String> SUPPORTED_SCHEMES = Set.of("file", "content", "http", "https");

    // 旧版 mpv AAR 无 MpvSubtitleContentLoader，@Override 已注释
//    @Override
    public SubtitleContent load(MediaItem item, Uri uri, Format format) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
        if (!SUPPORTED_SCHEMES.contains(UriUtil.scheme(uri))) return SubtitleContent.UNSUPPORTED;
        DataSource.Factory factory = new DefaultDataSource.Factory(App.get(), new OkHttpDataSource.Factory(OkHttp.player()).setDefaultRequestProperties(ExoUtil.extractHeaders(item)));
        return new DefaultSubtitleContentLoader(factory).load(item, uri, format);
    }
}
