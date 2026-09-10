package com.kusuu.pos

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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
        // Chỉ dùng ASCII cho text ESC/POS. Các dòng có tiếng Việt được render
        // bằng font Android thành raster để không phụ thuộc code-page của máy in.
        private val PRINTER_CHARSET: Charset = Charsets.US_ASCII
        private const val RASTER_WIDTH = 576
        private const val RASTER_TEXT_SIZE = 28f
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
         * Fast Vietnamese-safe path.
         *
         * The previous font-fix version rendered every Vietnamese line as a
         * separate GS v 0 bitmap. The printer then had to process many tiny
         * raster jobs, which caused the "giật giật / in từng chút" effect.
         *
         * We keep the same Vietnamese rendering, but compose the whole receipt
         * into ONE bitmap and send ONE raster command. This removes the repeated
         * raster-command pauses while keeping Vietnamese glyphs reliable on
         * generic 80mm ESC/POS printers.
         */
        private fun buildReceipt(r: JSONObject): ByteArray {
            data class PrintLine(val text: String, val bold: Boolean, val alignment: Int)

            val lines = mutableListOf<PrintLine>()
            fun line(s: String = "") = lines.add(PrintLine(s, false, 1))
            fun left(s: String = "") = lines.add(PrintLine(s, false, 0))
            fun right(s: String = "") = lines.add(PrintLine(s, false, 2))
            fun boldLine(s: String = "", alignment: Int = 1) = lines.add(PrintLine(s, true, alignment))

            boldLine(r.optString("shopName", "KU SỬU POS"))
            line("Địa chỉ: " + r.optString("address", ""))
            line("Điện thoại: " + r.optString("phone", ""))
            boldLine("HÓA ĐƠN BÁN HÀNG")
            line("Số HĐ: " + r.optString("invoiceNo", ""))
            line("Ngày: " + r.optString("date", ""))
            line("Bàn: " + r.optString("table", ""))
            line("Thu ngân: " + r.optString("cashier", "Ku Sửu"))
            line("-----------------------------------------------")

            val items = r.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name", "Món")
                    val qty = item.optDouble("qty", 0.0)
                    val total = item.optLong("total", 0L)
                    wrapText(name, 30).forEach { part -> left(part) }
                    val qtyText = formatQty(qty)
                    val unitPrice = formatMoney(item.optLong("price", 0L))
                    val money = formatMoney(total)
                    left("  x$qtyText  $unitPrice  $money")
                }
            }

            line("-----------------------------------------------")
            right("Tạm tính: " + formatMoney(r.optLong("subtotal", 0L)))
            val discount = r.optLong("discount", 0L)
            if (discount != 0L) right("Giảm giá: " + formatMoney(discount))
            boldLine("TỔNG THANH TOÁN: " + formatMoney(r.optLong("total", 0L)), 2)
            right("Thanh toán: " + r.optString("payment", "Tiền mặt"))
            right("")
            boldLine("CẢM ƠN QUÝ KHÁCH!")
            line("Hẹn gặp lại anh/chị.")
            line("")

            val width = RASTER_WIDTH
            val lineHeight = 38
            val topBottom = 12
            val height = (lines.size * lineHeight + topBottom * 2).coerceAtLeast(lineHeight)
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)

            val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = RASTER_TEXT_SIZE
                isSubpixelText = true
            }
            val boldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.BLACK
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                textSize = RASTER_TEXT_SIZE
                isSubpixelText = true
            }

            var y = topBottom + 28f
            for (item in lines) {
                val paint = if (item.bold) boldPaint else normalPaint
                val text = item.text.trimEnd()
                val measured = paint.measureText(text)
                val x = when (item.alignment) {
                    1 -> (width - measured) / 2f
                    2 -> width - measured - 8f
                    else -> 8f
                }.coerceAtLeast(0f)
                canvas.drawText(text, x, y, paint)
                y += lineHeight
            }

            val pixels = IntArray(width * height)
            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
            val widthBytes = (width + 7) / 8
            val raster = ByteArray(widthBytes * height)
            for (yy in 0 until height) {
                val row = yy * width
                val outRow = yy * widthBytes
                for (xx in 0 until width) {
                    val px = pixels[row + xx]
                    val rr = (px shr 16) and 0xFF
                    val gg = (px shr 8) and 0xFF
                    val bb = px and 0xFF
                    val gray = (rr * 299 + gg * 587 + bb * 114) / 1000
                    if (gray < 180) {
                        raster[outRow + (xx shr 3)] =
                            (raster[outRow + (xx shr 3)].toInt() or (0x80 shr (xx and 7))).toByte()
                    }
                }
            }
            bmp.recycle()

            val out = ByteArrayOutputStream(raster.size + 32)
            out.write(byteArrayOf(0x1B, 0x40))
            out.write(byteArrayOf(
                0x1D, 0x76, 0x30, 0x00,
                (widthBytes and 0xFF).toByte(), ((widthBytes shr 8) and 0xFF).toByte(),
                (height and 0xFF).toByte(), ((height shr 8) and 0xFF).toByte()
            ))
            out.write(raster)
            out.write(byteArrayOf(0x1B, 0x64, 0x03))
            out.write(byteArrayOf(0x1D, 0x56, 0x00))
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
