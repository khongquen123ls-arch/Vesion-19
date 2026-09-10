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
        private val PRINTER_CHARSET: Charset = Charset.forName("US-ASCII")
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
            fun line(s: String = "") {
                if (s.all { it.code < 128 }) {
                    text(s); cmd(0x0A)
                } else {
                    writeUnicodeLine(out, s)
                    cmd(0x0A)
                }
            }
            fun bold(on: Boolean) = cmd(0x1B, 0x45, if (on) 1 else 0)
            fun align(n: Int) = cmd(0x1B, 0x61, n)

            cmd(0x1B, 0x40) // initialize
            // Keep V19's direct ESC/POS text path. Unsupported Vietnamese glyphs
            // are rasterized only on the affected line; the rest stays byte-fast.
            align(1)
            bold(true); line(r.optString("shopName", "KU SỬU POS")); bold(false)
            line("Địa chỉ: " + r.optString("address", ""))
            line("ĐIỆN THOẠI: " + r.optString("phone", ""))
            bold(true); line("HÓA ĐƠN BÁN HÀNG"); bold(false)
            line("Số HĐ: " + r.optString("invoiceNo", ""))
            line("Ngày: " + r.optString("date", ""))
            line("Bàn: " + r.optString("table", ""))
            line("Thu ngân: " + r.optString("cashier", "Ku Sửu"))
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
                            line((left + right).take(LINE_WIDTH))
                        } else line("  " + part)
                    }
                }
            }
            line("-----------------------------------------------")
            align(2)
            line("Tạm tính: " + formatMoney(r.optLong("subtotal", 0L)))
            val discount = r.optLong("discount", 0L)
            if (discount != 0L) line("Giảm giá: " + formatMoney(discount))
            bold(true)
            line("TỔNG THANH TOÁN: " + formatMoney(r.optLong("total", 0L)))
            bold(false)
            line("Thanh toán: " + r.optString("payment", "Tiền mặt"))
            line("")
            bold(true); line("CẢM ƠN QUÝ KHÁCH!"); bold(false)
            line("Hẹn gặp lại anh/chị.")
            line("")
            cmd(0x1B, 0x64, 3) // feed 3
            cmd(0x1D, 0x56, 0) // cut
            return out.toByteArray()
        }

        private fun writeUnicodeLine(out: ByteArrayOutputStream, value: String) {
            // Render only the current Vietnamese/unicode line, not the whole receipt.
            // This keeps the V19 fast path for ASCII while Android's font engine
            // supplies proper Vietnamese glyphs. The bitmap is tightly cropped.
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textSize = 25f
                color = android.graphics.Color.BLACK
                isDither = false
            }
            val fm = paint.fontMetrics
            val pad = 2
            val width = kotlin.math.ceil(paint.measureText(value)).toInt().coerceIn(1, 576 - pad * 2) + pad * 2
            val height = kotlin.math.ceil(fm.bottom - fm.top).toInt().coerceAtLeast(24) + pad * 2
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(android.graphics.Color.WHITE)
                canvas.drawText(value, pad.toFloat(), pad - fm.top, paint)

                var left = width
                var right = -1
                var top = height
                var bottom = -1
                val pixels = IntArray(width)
                for (y in 0 until height) {
                    bmp.getPixels(pixels, 0, width, 0, y, width, 1)
                    for (x in 0 until width) {
                        if (android.graphics.Color.red(pixels[x]) < 200) {
                            if (x < left) left = x
                            if (x > right) right = x
                            if (y < top) top = y
                            if (y > bottom) bottom = y
                        }
                    }
                }
                if (right < left || bottom < top) return
                left = (left - 1).coerceAtLeast(0)
                right = (right + 1).coerceAtMost(width - 1)
                top = (top - 1).coerceAtLeast(0)
                bottom = (bottom + 1).coerceAtMost(height - 1)
                val cropW = right - left + 1
                val cropH = bottom - top + 1
                val wb = (cropW + 7) / 8
                val raster = ByteArray(wb * cropH)
                val row = IntArray(cropW)
                for (yy in 0 until cropH) {
                    bmp.getPixels(row, 0, cropW, left, top + yy, cropW, 1)
                    for (xx in 0 until cropW) {
                        if (android.graphics.Color.red(row[xx]) < 180) {
                            raster[yy * wb + (xx ushr 3)] =
                                (raster[yy * wb + (xx ushr 3)].toInt() or (0x80 ushr (xx and 7))).toByte()
                        }
                    }
                }
                // GS v 0 — printer receives one compact bitmap for this line.
                out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00))
                out.write(wb and 0xFF)
                out.write((wb ushr 8) and 0xFF)
                out.write(cropH and 0xFF)
                out.write((cropH ushr 8) and 0xFF)
                out.write(raster)
            } finally {
                bmp.recycle()
            }
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
