package com.tvbox.kotlin.core.ui

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** 二维码生成工具（基于 ZXing），失败返回 null，调用方需做空判断 */
object QRCodeGen {

    fun generateBitmap(content: String?, width: Int, height: Int, padding: Int = 4): Bitmap? {
        if (content.isNullOrEmpty() || width <= 0 || height <= 0) return null
        return try {
            val hints = HashMap<EncodeHintType, Any>().apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.MARGIN, padding)
            }
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, width, height, hints)
            val pixels = IntArray(width * height)
            // 深色模块使用不透明黑色，浅色使用不透明白色，保证任何背景下都能正常扫描
            val dark = 0xFF000000.toInt()
            val light = 0xFFFFFFFF.toInt()
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (matrix.get(x, y)) dark else light
                }
            }
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (_: Throwable) {
            null
        }
    }
}
