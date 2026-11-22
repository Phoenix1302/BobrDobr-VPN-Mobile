package com.bobrdobr.vpn.config

import org.bobrdobr.vpn.core.logger.DebugLogger


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.json.JSONObject
import java.net.URLDecoder

/**
 * QrConfigDecoder: Decodes WireGuard and AmneziaWG configs from QR code payloads or images.
 *
 * Supports:
 * 1. Raw [Interface] configuration text.
 * 2. Amnezia vpn:// URI scheme (Base64 or JSON containers with amnezia-awg / amnezia-wireguard).
 * 3. Base64-encoded WireGuard configurations.
 * 4. Image decoding from device gallery via ZXing.
 */
object QrConfigDecoder {

    private const val TAG = "QrConfigDecoder"

    /**
     * Parses raw string from barcode scanner and extracts WireGuard / AmneziaWG config text.
     */
    fun decodeRawText(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // 1. Direct [Interface] text
        if (trimmed.contains("[Interface]", ignoreCase = true)) {
            return trimmed
        }

        // 2. Amnezia "vpn://" link
        if (trimmed.startsWith("vpn://", ignoreCase = true)) {
            val stripped = trimmed.substring(6).trim()
            val urlDecoded = try {
                URLDecoder.decode(stripped, "UTF-8")
            } catch (e: Exception) {
                stripped
            }

            val b64Result = tryDecodeBase64(urlDecoded) ?: tryDecodeBase64(stripped)
            if (b64Result != null) {
                val fromB64 = decodeRawText(b64Result)
                if (fromB64 != null) return fromB64
            }
        }

        // 3. JSON format (Amnezia export / container scheme)
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                val json = JSONObject(trimmed)

                // Amnezia JSON with "containers" list
                val containers = json.optJSONArray("containers")
                if (containers != null) {
                    for (i in 0 until containers.length()) {
                        val containerObj = containers.getJSONObject(i)
                        val awgObj = containerObj.optJSONObject("awg")
                            ?: containerObj.optJSONObject("wireguard")
                        if (awgObj != null) {
                            val conf = awgObj.optString("config", "")
                            if (conf.contains("[Interface]", ignoreCase = true)) {
                                return conf
                            }
                            val lastConf = awgObj.optString("last_config", "")
                            if (lastConf.contains("[Interface]", ignoreCase = true)) {
                                return lastConf
                            }
                        }
                    }
                }

                // Direct keys in JSON
                for (key in listOf("config_text", "config", "rawConfig", "client_config")) {
                    val candidate = json.optString(key, "")
                    if (candidate.contains("[Interface]", ignoreCase = true)) {
                        return candidate
                    }
                }
            } catch (e: Exception) {
                DebugLogger.w(TAG, "Error parsing JSON QR payload", e)
            }
        }

        // 4. Standalone Base64 string
        val decodedBase64 = tryDecodeBase64(trimmed)
        if (decodedBase64 != null) {
            if (decodedBase64.contains("[Interface]", ignoreCase = true)) {
                return decodedBase64
            }
            // In case Base64 contained a JSON or nested payload
            val nested = decodeRawText(decodedBase64)
            if (nested != null) return nested
        }

        return null
    }

    /**
     * Decodes QR code from a gallery image URI using ZXing.
     */
    fun decodeFromUri(context: Context, uri: Uri): String? {
        val bitmap = loadBitmapFromUri(context, uri) ?: return null
        return decodeFromBitmap(bitmap)
    }

    /**
     * Decodes a QR code from a Bitmap via ZXing QRCodeReader.
     */
    fun decodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val reader = QRCodeReader()
            val result = reader.decode(binaryBitmap)
            val text = result.text ?: return null
            decodeRawText(text) ?: text
        } catch (e: Exception) {
            // Try multi-format reader as fallback
            try {
                val width = bitmap.width
                val height = bitmap.height
                val pixels = IntArray(width * height)
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

                val source = RGBLuminanceSource(width, height, pixels)
                val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                val multiReader = MultiFormatReader()
                val result = multiReader.decode(binaryBitmap)
                val text = result.text ?: return null
                decodeRawText(text) ?: text
            } catch (fallbackEx: Exception) {
                DebugLogger.w(TAG, "ZXing could not decode QR code from bitmap", fallbackEx)
                null
            }
        }
    }

    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
            } catch (ex: Exception) {
                DebugLogger.e(TAG, "Failed to load bitmap from uri: $uri", ex)
                null
            }
        }
    }

    private fun tryDecodeBase64(input: String): String? {
        return try {
            val bytes = Base64.decode(input, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            try {
                val bytes = Base64.decode(input, Base64.URL_SAFE)
                String(bytes, Charsets.UTF_8)
            } catch (ex: Exception) {
                null
            }
        }
    }
}
