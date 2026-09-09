package com.kusuu.pos

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {
    companion object {
        private const val WEBAPP_URL = "https://wispy-lake-0e02.tranbanguyen-ls2014.workers.dev/"
        private const val CONNECT_TIMEOUT_MS = 3500
        private const val SOCKET_TIMEOUT_MS = 10000
        private const val LINE_WIDTH = 48
        private val PRINTER_CHARSET: Charset = Charsets.US_ASCII
    }

    private lateinit var webView: WebView
    private val pool = Executors.newCachedThreadPool()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {}
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(PrinterBridge(), "KuSuuPrinter")
        webView.loadUrl(WEBAPP_URL)
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    inner class PrinterBridge {
        @JavascriptInterface
        fun probe(ip: String, port: Int): String {
            return try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip.trim(), port), CONNECT_TIMEOUT_MS)
                }
                JSONObject().put("ok", true).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Không kết nối được máy in").toString()
            }
        }

        /**
         * V19 fast path: nhận dữ liệu hóa đơn dạng JSON nhỏ và tự dựng ESC/POS
         * ngay trên Android. Không còn render toàn hóa đơn thành bitmap/Base64.
         */
        @JavascriptInterface
        fun printReceipt(ip: String, port: Int, receiptJson: String): String {
            return try {
                val receipt = JSONObject(receiptJson)
                val bytes = buildReceipt(receipt)
                sendBytes(ip, port, bytes)
                JSONObject().put("ok", true).put("bytes", bytes.size).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Không in được").toString()
            }
        }

        /** V18 compatibility / fallback for printers that need raster output. */
        @JavascriptInterface
        fun printRaster(ip: String, port: Int, widthBytes: Int, base64: String): String {
            return try {
                val raster = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val cmd = ByteArrayOutputStream()
                cmd.write(byteArrayOf(0x1B, 0x40))
                cmd.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
                cmd.write(widthBytes and 0xFF)
                cmd.write((widthBytes shr 8) and 0xFF)
                val height = if (widthBytes > 0) raster.size / widthBytes else 0
                cmd.write(height and 0xFF)
                cmd.write((height shr 8) and 0xFF)
                cmd.write(raster)
                cmd.write(byteArrayOf(0x0A, 0x0A, 0x0A))
                cmd.write(byteArrayOf(0x1D, 0x56, 0x00))
                sendBytes(ip, port, cmd.toByteArray())
                JSONObject().put("ok", true).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", e.message ?: "Không in được").toString()
            }
        }

        private fun sendBytes(ip: String, port: Int, bytes: ByteArray) {
            Socket().use { s ->
                s.tcpNoDelay = true
                s.soTimeout = SOCKET_TIMEOUT_MS
                s.connect(InetSocketAddress(ip.trim(), port), CONNECT_TIMEOUT_MS)
                s.getOutputStream().use { out ->
                    out.write(bytes)
                    out.flush()
                }
            }
        }

        private fun buildReceipt(r: JSONObject): ByteArray {
            val out = ByteArrayOutputStream()
            fun cmd(vararg b: Int) = out.write(b.map { (it and 0xFF).toByte() }.toByteArray())
            fun text(s: String) = out.write(s.toByteArray(PRINTER_CHARSET))
            fun line(s: String = "") { text(s); cmd(0x0A) }
            fun bold(on: Boolean) = cmd(0x1B, 0x45, if (on) 1 else 0)
            fun align(n: Int) = cmd(0x1B, 0x61, n)

            cmd(0x1B, 0x40) // initialize
            // V19.1: không ép codepage Windows-1258 vì nhiều máy in không hỗ trợ
            // combining marks tiếng Việt. Chuỗi có ký tự ngoài ASCII sẽ được rasterize
            // bằng font Android (Unicode) rồi gửi ESC/POS bitmap; chữ ASCII vẫn đi native.
            align(1)
            smartLine(out, r.optString("shopName", "KU SUU POS"), true, 26, 1)
            smartLine(out, if (r.optString("address").isNotBlank()) "Địa chỉ: ${r.optString("address")}" else "", false, 20, 1)
            smartLine(out, if (r.optString("phone").isNotBlank()) "ĐIỆN THOẠI: ${r.optString("phone")}" else "", false, 20, 1)
            smartLine(out, "HÓA ĐƠN BÁN HÀNG", true, 25, 1)
            smartLine(out, "Số HĐ: " + r.optString("invoiceNo", ""), false, 20, 1)
            smartLine(out, "Ngày: " + r.optString("date", ""), false, 20, 1)
            smartLine(out, "Bàn: " + r.optString("table", ""), false, 20, 1)
            smartLine(out, "Thu ngân: " + r.optString("cashier", "Ku Suu"), false, 20, 1)
            line("-----------------------------------------------")

            align(0)
            val items = r.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name", "Món")
                    val qty = item.optDouble("qty", 0.0)
                    val total = item.optLong("total", 0L)
                    wrapText(name, 32).forEachIndexed { idx, part ->
                        if (idx == 0) {
                            val qtyText = formatQty(qty)
                            val money = formatMoney(total)
                            val left = part.take(32).padEnd(32, ' ')
                            val right = "x$qtyText".padStart(6) + money.padStart(10)
                            smartLine(out, (left + right).take(LINE_WIDTH), false, 20, 0)
                        } else smartLine(out, "  " + part, false, 20, 0)
                    }
                }
            }
            line("-----------------------------------------------")
            align(2)
            smartLine(out, "Tạm tính: " + formatMoney(r.optLong("subtotal", 0L)), false, 20, 2)
            val discount = r.optLong("discount", 0L)
            if (discount != 0L) smartLine(out, "Giảm giá: " + formatMoney(discount), false, 20, 2)
            smartLine(out, "TỔNG THANH TOÁN: " + formatMoney(r.optLong("total", 0L)), true, 24, 2)
            smartLine(out, "Thanh toán: " + r.optString("payment", "Tien mat"), false, 20, 2)
            line("")
            smartLine(out, "CẢM ƠN QUÝ KHÁCH!", true, 20, 1)
            smartLine(out, "Hẹn gặp lại anh/chị.", false, 18, 1)
            line("")
            cmd(0x1B, 0x64, 3) // feed 3
            cmd(0x1D, 0x56, 0) // cut
            return out.toByteArray()
        }

        private fun smartLine(out: ByteArrayOutputStream, value: String, isBold: Boolean, textSize: Int, alignment: Int) {
            if (value.isBlank()) return
            val ascii = value.all { it.code in 32..126 }
            if (ascii) {
                out.write(value.toByteArray(Charsets.US_ASCII))
                out.write(0x0A)
            } else {
                out.write(renderTextLine(value, isBold, textSize, alignment))
            }
        }

        /** Render only Unicode lines as ESC/POS raster. This keeps the fast native path
         * for ASCII while guaranteeing Vietnamese glyphs regardless of printer codepage. */
        private fun renderTextLine(value: String, isBold: Boolean, textSize: Int, alignment: Int): ByteArray {
            val width = 576
            val padding = 18
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", if (isBold) Typeface.BOLD else Typeface.NORMAL)
                this.textSize = textSize.toFloat()
                color = android.graphics.Color.BLACK
                isSubpixelText = true
            }
            val maxWidth = width - padding * 2
            val measured = minOf(paint.measureText(value), maxWidth.toFloat())
            val height = (textSize * 1.45f).toInt().coerceAtLeast(30)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val x = when (alignment) {
                1 -> (width - measured) / 2f
                2 -> width - padding - measured
                else -> padding.toFloat()
            }
            val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
            canvas.drawText(value, x, baseline, paint)

            val widthBytes = width / 8
            val raster = ByteArray(widthBytes * height)
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            for (y in 0 until height) {
                for (x0 in 0 until width) {
                    val c = pixels[y * width + x0]
                    val r = android.graphics.Color.red(c)
                    val g = android.graphics.Color.green(c)
                    val b = android.graphics.Color.blue(c)
                    if ((r + g + b) / 3 < 180) {
                        val idx = y * widthBytes + (x0 shr 3)
                        raster[idx] = (raster[idx].toInt() or (0x80 shr (x0 and 7))).toByte()
                    }
                }
            }
            bitmap.recycle()
            val cmd = ByteArrayOutputStream()
            cmd.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
            cmd.write(widthBytes and 0xFF); cmd.write((widthBytes shr 8) and 0xFF)
            cmd.write(height and 0xFF); cmd.write((height shr 8) and 0xFF)
            cmd.write(raster)
            cmd.write(0x0A)
            return cmd.toByteArray()
        }

        private fun wrapText(value: String, max: Int): List<String> {
            val s = value.trim().replace(Regex("\\s+"), " ")
            if (s.isEmpty()) return listOf("")
            val result = mutableListOf<String>()
            var rest = s
            while (rest.length > max) {
                var cut = rest.lastIndexOf(' ', max)
                if (cut < max / 2) cut = max
                result += rest.substring(0, cut).trim()
                rest = rest.substring(cut).trim()
            }
            if (rest.isNotEmpty()) result += rest
            return result
        }

        private fun formatQty(q: Double): String {
            return if (q % 1.0 == 0.0) q.toInt().toString() else String.format("%.2f", q).trimEnd('0').trimEnd('.')
        }

        private fun formatMoney(v: Long): String {
            val s = kotlin.math.abs(v).toString()
            val grouped = s.reversed().chunked(3).joinToString(".").reversed()
            return (if (v < 0) "-" else "") + grouped + " đ"
        }
    }
}
