package com.fongmi.android.tv.ui.dialog;

import android.app.Activity
import android.view.LayoutInflater
import android.view.WindowManager

import androidx.appcompat.app.AlertDialog
import com.fongmi.android.tv.databinding.DialogTbsDebugBinding
import com.fongmi.android.tv.utils.ResUtil
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class TbsDebugDialog {

    private val binding: DialogTbsDebugBinding
    private val dialog: AlertDialog

    companion object {
        private const val DEBUG_URL = "https://debugtbs.qq.com"

        @JvmStatic
        fun create(activity: Activity): TbsDebugDialog {
            return TbsDebugDialog(activity)
        }
    }

    private constructor(activity: Activity) {
        binding = DialogTbsDebugBinding.inflate(LayoutInflater.from(activity))
        dialog = MaterialAlertDialogBuilder(activity).setView(binding.root).create()
    }

    fun show() {
        initDialog()
        initView()
    }

    private fun initDialog() {
        dialog.window?.apply {
            val params = attributes
            params.width = (ResUtil.getScreenWidth() * 0.8f).toInt()
            params.height = (ResUtil.getScreenHeight() * 0.8f).toInt()
            attributes = params
            setDimAmount(0f)
        }
        dialog.show()
    }

    private fun initView() {
        binding.webView.loadUrl(DEBUG_URL)
    }
}
