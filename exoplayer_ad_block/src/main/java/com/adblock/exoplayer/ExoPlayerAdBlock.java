package com.exo.adblock;

import androidx.media3.exoplayer.ExoPlayer;

public class ExoPlayerAdBlock {

    // 调用方式：ExoPlayerAdBlock.setAdBlock(exoPlayer, true);
    public static void setAdBlock(ExoPlayer exoPlayer, boolean enable) {
        AdBlockHolder.setAdBlockEnabled(enable);
    }
}