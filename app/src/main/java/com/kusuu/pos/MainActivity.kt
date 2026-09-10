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
        private val PRINTER_CHARSET: Charset = Charset.forName("windows-1258")
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

        /**
         * V20 CUKCUK-style print path.
         *
         * The whole receipt is rendered by Android using a Unicode-capable font,
         * converted to a compact 1-bit ESC/POS raster, then sent in ONE TCP write.
         * This avoids every printer-side Vietnamese code-page dependency while
         * preserving V19's single-connection / single-payload transport path.
         */
        private fun buildReceipt(r: JSONObject): ByteArray {
            val width = 576
            val lineHeight = 30
            val top = 18
            val bottom = 30
            val lines = mutableListOf<ReceiptLine>()

            fun add(text: String = "", align: Int = 0, bold: Boolean = false, size: Float = 24f) {
                lines += ReceiptLine(text, align, bold, size)
            }
            fun separator() = add("-".repeat(56), 0, false, 20f)

            add(r.optString("shopName", "KU SỬU POS"), 1, true, 30f)
            add("Địa chỉ: " + r.optString("address", ""), 1)
            add("ĐIỆN THOẠI: " + r.optString("phone", ""), 1)
            add("HÓA ĐƠN BÁN HÀNG", 1, true, 28f)
            add("Số HĐ: " + r.optString("invoiceNo", ""), 1)
            add("Ngày: " + r.optString("date", ""), 1)
            add("Bàn: " + r.optString("table", ""), 1)
            add("Thu ngân: " + r.optString("cashier", "Ku Sửu"), 1)
            separator()

            val items = r.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name", "Món")
                    val qty = item.optDouble("qty", 0.0)
                    val total = item.optLong("total", 0L)
                    val qtyText = "x" + formatQty(qty)
                    val money = formatMoney(total)
                    val first = wrapText(name, 30)
                    if (first.isEmpty()) {
                        add("$qtyText $money", 0)
                    } else {
                        val left = first[0]
                        val right = qtyText.padStart(6) + money.padStart(12)
                        add(left + right, 0, false, 23f)
                        for (j in 1 until first.size) add("  " + first[j], 0, false, 23f)
                    }
                }
            }
            separator()
            add("Tạm tính: " + formatMoney(r.optLong("subtotal", 0L)), 2)
            val discount = r.optLong("discount", 0L)
            if (discount != 0L) add("Giảm giá: " + formatMoney(discount), 2)
            add("TỔNG THANH TOÁN: " + formatMoney(r.optLong("total", 0L)), 2, true, 26f)
            add("Thanh toán: " + r.optString("payment", "Tiền mặt"), 2)
            add("")
            add("CẢM ƠN QUÝ KHÁCH!", 1, true, 27f)
            add("Hẹn gặp lại anh/chị.", 1)

            return renderReceiptRaster(lines, width, lineHeight, top, bottom)
        }

        private data class ReceiptLine(
            val text: String,
            val align: Int,
            val bold: Boolean,
            val size: Float
        )

        private fun renderReceiptRaster(
            lines: List<ReceiptLine>,
            width: Int,
            lineHeight: Int,
            top: Int,
            bottom: Int
        ): ByteArray {
            val height = top + lines.size * lineHeight + bottom
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                color = android.graphics.Color.BLACK
                typeface = Typeface.create("sans", Typeface.NORMAL)
                textAlign = Paint.Align.LEFT
                isDither = false
            }

            var y = top.toFloat()
            for (line in lines) {
                paint.textSize = line.size
                paint.typeface = Typeface.create("sans", if (line.bold) Typeface.BOLD else Typeface.NORMAL)
                val metrics = paint.fontMetrics
                val baseline = y - metrics.ascent
                val textWidth = paint.measureText(line.text)
                val x = when (line.align) {
                    1 -> (width - textWidth) / 2f
                    2 -> (width - textWidth - 8f).coerceAtLeast(8f)
                    else -> 8f
                }
                canvas.drawText(line.text, x, baseline, paint)
                y += lineHeight
            }

            // Convert to packed 1-bit ESC/POS raster data. Crop only empty rows
            // at the bottom so the payload is as small as possible.
            val pixels = IntArray(width)
            var lastBlackRow = 0
            for (row in height - 1 downTo 0) {
                bitmap.getPixels(pixels, 0, width, 0, row, width, 1)
                var black = false
                for (x in pixels.indices) {
                    val c = pixels[x]
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    // Slightly conservative threshold keeps Vietnamese accents crisp.
                    if ((rr + gg + bb) < 620) {
                        black = true
                        break
                    }
                }
                if (black) {
                    lastBlackRow = row
                    break
                }
            }
            val usedHeight = (lastBlackRow + 12).coerceAtMost(height)
            val widthBytes = (width + 7) / 8
            val raster = ByteArray(widthBytes * usedHeight)
            val rowPixels = IntArray(width)
            for (row in 0 until usedHeight) {
                bitmap.getPixels(rowPixels, 0, width, 0, row, width, 1)
                val base = row * widthBytes
                for (x in 0 until width) {
                    val c = rowPixels[x]
                    val rr = (c shr 16) and 0xFF
                    val gg = (c shr 8) and 0xFF
                    val bb = c and 0xFF
                    if ((rr + gg + bb) < 620) {
                        raster[base + (x shr 3)] =
                            (raster[base + (x shr 3)].toInt() or (0x80 shr (x and 7))).toByte()
                    }
                }
            }
            bitmap.recycle()

            val out = ByteArrayOutputStream(raster.size + 32)
            fun cmd(vararg b: Int) = out.write(b.map { (it and 0xFF).toByte() }.toByteArray())
            cmd(0x1B, 0x40)
            // GS v 0: normal-density, 1-bit raster image.
            cmd(0x1D, 0x76, 0x30, 0x00)
            cmd(widthBytes and 0xFF, (widthBytes shr 8) and 0xFF)
            cmd(usedHeight and 0xFF, (usedHeight shr 8) and 0xFF)
            out.write(raster)
            cmd(0x0A, 0x0A, 0x0A)
            cmd(0x1D, 0x56, 0x00)
            return out.toByteArray()
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
