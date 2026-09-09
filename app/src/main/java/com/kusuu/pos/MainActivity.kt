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
            fun align(n: Int) = cmd(0x1B, 0x61, n)
            fun lineFeed() = cmd(0x0A)

            cmd(0x1B, 0x40) // initialize

            // ===== HEADER — theo đúng mẫu phiếu tính tiền =====
            smartLine(out, r.optString("shopName", "KU SỬU POS"), true, 23, 1)
            val address = r.optString("address", "").trim()
            if (address.isNotBlank()) smartLine(out, "Địa chỉ: $address", false, 18, 1)
            val phone = r.optString("phone", "").trim()
            if (phone.isNotBlank()) smartLine(out, "Điện thoại: $phone", false, 18, 1)
            line("", false, 18, 0)
            smartLine(out, "PHIẾU TÍNH TIỀN", true, 25, 1)

            // Metadata — căn trái, giống mẫu.
            line("Bàn: ${r.optString("table", "Bàn")}", false, 18, 0)
            val invoiceNo = r.optString("invoiceNo", "")
            val date = r.optString("date", "")
            if (invoiceNo.isNotBlank() || date.isNotBlank()) {
                line("Số phiếu: $invoiceNo - Ngày: $date", false, 18, 0)
            }
            val timeIn = r.optString("timeIn", "")
            val timeOut = r.optString("timeOut", "")
            if (timeIn.isNotBlank() || timeOut.isNotBlank()) {
                line("Giờ vào: $timeIn - Giờ ra: $timeOut", false, 18, 0)
            }
            line("Thu ngân: ${r.optString("cashier", "Ku Sửu")}", false, 18, 0)

            // ===== BẢNG MÓN =====
            // 48 cột: TT(3) + Tên hàng(18) + SL(4) + Đơn giá(10) + Thành tiền(13)
            tableLine(out, "TT", "Tên hàng", "SL", "Đơn giá", "Thành tiền", true)
            divider(out)

            val items = r.optJSONArray("items")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val name = item.optString("name", "Món")
                    val qty = item.optDouble("qty", 0.0)
                    val price = item.optLong("price", 0L)
                    val total = item.optLong("total", 0L)
                    val nameParts = wrapText(name, 18)
                    nameParts.forEachIndexed { idx, part ->
                        tableLine(
                            out,
                            if (idx == 0) (i + 1).toString() else "",
                            part,
                            if (idx == 0) formatQty(qty) else "",
                            if (idx == 0) formatMoneyNoCurrency(price) else "",
                            if (idx == 0) formatMoneyNoCurrency(total) else "",
                            false
                        )
                    }
                    dottedDivider(out)
                }
            }

            // ===== TỔNG TIỀN =====
            divider(out)
            totalRow(out, "TỔNG CỘNG:", formatMoneyNoCurrency(r.optLong("total", 0L)))
            val discount = r.optLong("discount", 0L)
            if (discount != 0L) totalRow(out, "Giảm giá:", formatMoneyNoCurrency(discount))
            val taxRate = r.optString("taxRate", "0%")
            totalRow(out, "Thuế GTGT", formatMoneyNoCurrency(r.optLong("tax", 0L)), taxRate)

            lineFeed()
            smartLine(out, "CẢM ƠN QUÝ KHÁCH", true, 20, 1)
            lineFeed()
            cmd(0x1B, 0x64, 3) // feed 3
            cmd(0x1D, 0x56, 0) // cut
            return out.toByteArray()
        }

        private fun line(out: ByteArrayOutputStream, value: String, isBold: Boolean, textSize: Int, alignment: Int) {
            if (value.isBlank()) {
                out.write(0x0A)
                return
            }
            smartLine(out, value, isBold, textSize, alignment)
        }

        private fun divider(out: ByteArrayOutputStream) {
            smartLine(out, "-".repeat(LINE_WIDTH), false, 16, 0)
        }

        private fun dottedDivider(out: ByteArrayOutputStream) {
            smartLine(out, ".".repeat(LINE_WIDTH), false, 14, 0)
        }

        /** Fixed-width table renderer. Monospace makes every column land at the same x position. */
        private fun tableLine(
            out: ByteArrayOutputStream,
            no: String,
            name: String,
            qty: String,
            price: String,
            total: String,
            header: Boolean
        ) {
            val n = no.take(3).padEnd(3)
            val item = name.take(18).padEnd(18)
            val q = qty.take(4).padStart(4)
            val p = price.take(10).padStart(10)
            val t = total.take(13).padStart(13)
            val value = (n + item + q + p + t).take(LINE_WIDTH)
            smartLine(out, value, header, if (header) 17 else 16, 0, mono = true)
        }

        private fun totalRow(out: ByteArrayOutputStream, label: String, value: String, middle: String = "") {
            val left = label.take(31).padEnd(31)
            val mid = middle.take(4).padStart(4)
            val right = value.take(13).padStart(13)
            smartLine(out, (left + mid + right).take(LINE_WIDTH), label.startsWith("TỔNG"), 19, 0, mono = true)
        }

        private fun smartLine(out: ByteArrayOutputStream, value: String, isBold: Boolean, textSize: Int, alignment: Int, mono: Boolean = false) {
            if (value.isBlank()) return
            val ascii = value.all { it.code in 32..126 }
            if (ascii) {
                out.write(value.toByteArray(Charsets.US_ASCII))
                out.write(0x0A)
            } else {
                out.write(renderTextLine(value, isBold, textSize, alignment, mono))
            }
        }

        /** Render only Unicode lines as ESC/POS raster. This keeps the fast native path
         * for ASCII while guaranteeing Vietnamese glyphs regardless of printer codepage. */
        private fun renderTextLine(value: String, isBold: Boolean, textSize: Int, alignment: Int, mono: Boolean = false): ByteArray {
            val width = 576
            val padding = 18
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = if (mono) Typeface.create("monospace", if (isBold) Typeface.BOLD else Typeface.NORMAL)
                else Typeface.create("sans-serif", if (isBold) Typeface.BOLD else Typeface.NORMAL)
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

        private fun formatMoneyNoCurrency(v: Long): String {
            val s = kotlin.math.abs(v).toString()
            val grouped = s.reversed().chunked(3).joinToString(".").reversed()
            return (if (v < 0) "-" else "") + grouped
        }

        private fun formatMoney(v: Long): String {
            val s = kotlin.math.abs(v).toString()
            val grouped = s.reversed().chunked(3).joinToString(".").reversed()
            return (if (v < 0) "-" else "") + grouped + " đ"
        }
    }
}
