package com.fongmi.android.tv.ui.adapter.x5;

import android.util.Log

object WebpageAdapterManager {

    private const val TAG = "WebpageAdapterManager"

    private val supportedWebpageAdapters = listOf(
        TbsDebugWebpageAdapter()
    )

    fun get(url: String): WebpageAdapter {
        var adapter = supportedWebpageAdapters[supportedWebpageAdapters.lastIndex]
        for (a in supportedWebpageAdapters) {
            if (a.isAdaptedUrl(url)) {
                adapter = a
                break
            }
        }
        Log.i(TAG, "Use ${adapter.javaClass.simpleName} for $url")
        return adapter
    }
}