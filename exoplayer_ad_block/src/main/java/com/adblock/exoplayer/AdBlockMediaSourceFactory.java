package com.exo.adblock;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.source.MediaSource;

public final class AdBlockMediaSourceFactory implements MediaSource.Factory {

    private final MediaSource.Factory delegate;

    public AdBlockMediaSourceFactory(MediaSource.Factory delegate) {
        this.delegate = delegate;
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
        if (!AdBlockHolder.isAdBlockEnabled()) {
            return delegate.createMediaSource(mediaItem);
        }

        MediaItem noAdMediaItem = mediaItem.buildUpon()
                .setAdsConfiguration(null)
                .build();

        return delegate.createMediaSource(noAdMediaItem);
    }

    @Override
    public int[] getSupportedTypes() {
        return delegate.getSupportedTypes();
    }
}