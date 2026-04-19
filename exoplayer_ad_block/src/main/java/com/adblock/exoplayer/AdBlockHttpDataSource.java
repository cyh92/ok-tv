package com.exo.adblock;

import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import java.io.IOException;

public final class AdBlockHttpDataSource extends DefaultHttpDataSource {

    private static final String[] AD_KEYWORDS = {
            "ad", "ads", "googleads", "doubleclick", "advertisement",
            "pre-roll", "mid-roll", "post-roll", "commercial"
    };

    public AdBlockHttpDataSource() {
        super("ExoPlayer-AdBlock");
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (!AdBlockHolder.isAdBlockEnabled()) {
            return super.open(dataSpec);
        }

        String url = dataSpec.uri.toString().toLowerCase();
        for (String keyword : AD_KEYWORDS) {
            if (url.contains(keyword)) {
                throw new IOException("Ad blocked: " + url);
            }
        }
        return super.open(dataSpec);
    }

    public static final class Factory implements DataSource.Factory {
        @Override
        public DataSource createDataSource() {
            return new AdBlockHttpDataSource();
        }
    }
}