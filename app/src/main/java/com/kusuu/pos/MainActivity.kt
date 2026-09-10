package com.kusuu.pos

import android.annotation.SuppressLint
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
import java.util.concurrent.Executors

class MainActivity : android.app.Activity() {
    companion object {
        private const val WEBAPP_URL = "https://wispy-lake-0e02.tranbanguyen-ls2014.workers.dev/"
        private const val CONNECT_TIMEOUT_MS = 3500
        private const val SOCKET_TIMEOUT_MS = 10000
        private const val LINE_WIDTH = 48
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
            fun text(s: String) { out.write(encodeTcvn3(s)) }
            fun line(s: String = "") { text(s); cmd(0x0A) }
            fun bold(on: Boolean) = cmd(0x1B, 0x45, if (on) 1 else 0)
            fun align(n: Int) = cmd(0x1B, 0x61, n)

            cmd(0x1B, 0x40) // initialize
            // TCVN-3 Vietnamese: page 30 (lowercase) / 31 (uppercase).
            // Keep the V19 direct ESC/POS byte-stream path; only text encoding changes.
            cmd(0x1B, 0x74, 30)
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

        /**
         * Encode Unicode Vietnamese text directly to the printer's TCVN-3 pages.
         * Page 30 is the lowercase TCVN-3 table; page 31 is the uppercase table.
         * No bitmap/raster rendering is used, so the V19 fast TCP/ESC-POS path is preserved.
         */
        private fun encodeTcvn3(value: String): ByteArray {
            val out = ByteArrayOutputStream()
            var page = 30
            fun selectPage(target: Int) {
                if (page != target) {
                    out.write(byteArrayOf(0x1B, 0x74, target.toByte()))
                    page = target
                }
            }
            for (ch in value) {
                val encoded = tcvn3Bytes(ch)
                if (encoded == null) {
                    if (ch.code <= 0x7F) {
                        out.write(ch.code)
                    } else {
                        out.write('?'.code)
                    }
                    continue
                }
                val targetPage = if (ch.isUpperCase()) 31 else 30
                selectPage(targetPage)
                out.write(encoded)
            }
            return out.toByteArray()
        }

        private fun tcvn3Bytes(ch: Char): ByteArray? = when (ch) {
            '\u00C0' -> byteArrayOf(0x41, 0xB5.toByte()) // À
            '\u00C1' -> byteArrayOf(0x41, 0xB8.toByte()) // Á
            '\u00C2' -> byteArrayOf(0xA2.toByte()) // Â
            '\u00C3' -> byteArrayOf(0x41, 0xB7.toByte()) // Ã
            '\u00C8' -> byteArrayOf(0x45, 0xCC.toByte()) // È
            '\u00C9' -> byteArrayOf(0x45, 0xD0.toByte()) // É
            '\u00CA' -> byteArrayOf(0xA3.toByte()) // Ê
            '\u00CC' -> byteArrayOf(0x49, 0xD7.toByte()) // Ì
            '\u00CD' -> byteArrayOf(0x49, 0xDD.toByte()) // Í
            '\u00D2' -> byteArrayOf(0x4F, 0xDF.toByte()) // Ò
            '\u00D3' -> byteArrayOf(0x4F, 0xE3.toByte()) // Ó
            '\u00D4' -> byteArrayOf(0xA4.toByte()) // Ô
            '\u00D5' -> byteArrayOf(0x4F, 0xE2.toByte()) // Õ
            '\u00D9' -> byteArrayOf(0x55, 0xEF.toByte()) // Ù
            '\u00DA' -> byteArrayOf(0x55, 0xF3.toByte()) // Ú
            '\u00DD' -> byteArrayOf(0x59, 0xFD.toByte()) // Ý
            '\u00E0' -> byteArrayOf(0xB5.toByte()) // à
            '\u00E1' -> byteArrayOf(0xB8.toByte()) // á
            '\u00E2' -> byteArrayOf(0xA9.toByte()) // â
            '\u00E3' -> byteArrayOf(0xB7.toByte()) // ã
            '\u00E8' -> byteArrayOf(0xCC.toByte()) // è
            '\u00E9' -> byteArrayOf(0xD0.toByte()) // é
            '\u00EA' -> byteArrayOf(0xAA.toByte()) // ê
            '\u00EC' -> byteArrayOf(0xD7.toByte()) // ì
            '\u00ED' -> byteArrayOf(0xDD.toByte()) // í
            '\u00F2' -> byteArrayOf(0xDF.toByte()) // ò
            '\u00F3' -> byteArrayOf(0xE3.toByte()) // ó
            '\u00F4' -> byteArrayOf(0xAB.toByte()) // ô
            '\u00F5' -> byteArrayOf(0xE2.toByte()) // õ
            '\u00F9' -> byteArrayOf(0xEF.toByte()) // ù
            '\u00FA' -> byteArrayOf(0xF3.toByte()) // ú
            '\u00FD' -> byteArrayOf(0xFD.toByte()) // ý
            '\u0102' -> byteArrayOf(0xA1.toByte()) // Ă
            '\u0103' -> byteArrayOf(0xA8.toByte()) // ă
            '\u0110' -> byteArrayOf(0xA7.toByte()) // Đ
            '\u0111' -> byteArrayOf(0xAE.toByte()) // đ
            '\u0128' -> byteArrayOf(0x49, 0xDC.toByte()) // Ĩ
            '\u0129' -> byteArrayOf(0xDC.toByte()) // ĩ
            '\u0168' -> byteArrayOf(0x55, 0xF2.toByte()) // Ũ
            '\u0169' -> byteArrayOf(0xF2.toByte()) // ũ
            '\u01A0' -> byteArrayOf(0xA5.toByte()) // Ơ
            '\u01A1' -> byteArrayOf(0xAC.toByte()) // ơ
            '\u01AF' -> byteArrayOf(0xA6.toByte()) // Ư
            '\u01B0' -> byteArrayOf(0xAD.toByte()) // ư
            '\u1EA0' -> byteArrayOf(0x41, 0xB9.toByte()) // Ạ
            '\u1EA1' -> byteArrayOf(0xB9.toByte()) // ạ
            '\u1EA2' -> byteArrayOf(0x41, 0xB6.toByte()) // Ả
            '\u1EA3' -> byteArrayOf(0xB6.toByte()) // ả
            '\u1EA4' -> byteArrayOf(0xA2.toByte(), 0xCA.toByte()) // Ấ
            '\u1EA5' -> byteArrayOf(0xCA.toByte()) // ấ
            '\u1EA6' -> byteArrayOf(0xA2.toByte(), 0xC7.toByte()) // Ầ
            '\u1EA7' -> byteArrayOf(0xC7.toByte()) // ầ
            '\u1EA8' -> byteArrayOf(0xA2.toByte(), 0xC8.toByte()) // Ẩ
            '\u1EA9' -> byteArrayOf(0xC8.toByte()) // ẩ
            '\u1EAA' -> byteArrayOf(0xA2.toByte(), 0xC9.toByte()) // Ẫ
            '\u1EAB' -> byteArrayOf(0xC9.toByte()) // ẫ
            '\u1EAC' -> byteArrayOf(0xA2.toByte(), 0xCB.toByte()) // Ậ
            '\u1EAD' -> byteArrayOf(0xCB.toByte()) // ậ
            '\u1EAE' -> byteArrayOf(0xA1.toByte(), 0xBE.toByte()) // Ắ
            '\u1EAF' -> byteArrayOf(0xBE.toByte()) // ắ
            '\u1EB0' -> byteArrayOf(0xA1.toByte(), 0xBB.toByte()) // Ằ
            '\u1EB1' -> byteArrayOf(0xBB.toByte()) // ằ
            '\u1EB2' -> byteArrayOf(0xA1.toByte(), 0xBC.toByte()) // Ẳ
            '\u1EB3' -> byteArrayOf(0xBC.toByte()) // ẳ
            '\u1EB4' -> byteArrayOf(0xA1.toByte(), 0xBD.toByte()) // Ẵ
            '\u1EB5' -> byteArrayOf(0xBD.toByte()) // ẵ
            '\u1EB6' -> byteArrayOf(0xA1.toByte(), 0xC6.toByte()) // Ặ
            '\u1EB7' -> byteArrayOf(0xC6.toByte()) // ặ
            '\u1EB8' -> byteArrayOf(0x45, 0xD1.toByte()) // Ẹ
            '\u1EB9' -> byteArrayOf(0xD1.toByte()) // ẹ
            '\u1EBA' -> byteArrayOf(0x45, 0xCE.toByte()) // Ẻ
            '\u1EBB' -> byteArrayOf(0xCE.toByte()) // ẻ
            '\u1EBC' -> byteArrayOf(0x45, 0xCF.toByte()) // Ẽ
            '\u1EBD' -> byteArrayOf(0xCF.toByte()) // ẽ
            '\u1EBE' -> byteArrayOf(0xA3.toByte(), 0xD5.toByte()) // Ế
            '\u1EBF' -> byteArrayOf(0xD5.toByte()) // ế
            '\u1EC0' -> byteArrayOf(0xA3.toByte(), 0xD2.toByte()) // Ề
            '\u1EC1' -> byteArrayOf(0xD2.toByte()) // ề
            '\u1EC2' -> byteArrayOf(0xA3.toByte(), 0xD3.toByte()) // Ể
            '\u1EC3' -> byteArrayOf(0xD3.toByte()) // ể
            '\u1EC4' -> byteArrayOf(0xA3.toByte(), 0xD4.toByte()) // Ễ
            '\u1EC5' -> byteArrayOf(0xD4.toByte()) // ễ
            '\u1EC6' -> byteArrayOf(0xA3.toByte(), 0xD6.toByte()) // Ệ
            '\u1EC7' -> byteArrayOf(0xD6.toByte()) // ệ
            '\u1EC8' -> byteArrayOf(0x49, 0xD8.toByte()) // Ỉ
            '\u1EC9' -> byteArrayOf(0xD8.toByte()) // ỉ
            '\u1ECA' -> byteArrayOf(0x49, 0xDE.toByte()) // Ị
            '\u1ECB' -> byteArrayOf(0xDE.toByte()) // ị
            '\u1ECC' -> byteArrayOf(0x4F, 0xE4.toByte()) // Ọ
            '\u1ECD' -> byteArrayOf(0xE4.toByte()) // ọ
            '\u1ECE' -> byteArrayOf(0x4F, 0xE1.toByte()) // Ỏ
            '\u1ECF' -> byteArrayOf(0xE1.toByte()) // ỏ
            '\u1ED0' -> byteArrayOf(0xA4.toByte(), 0xE8.toByte()) // Ố
            '\u1ED1' -> byteArrayOf(0xE8.toByte()) // ố
            '\u1ED2' -> byteArrayOf(0xA4.toByte(), 0xE5.toByte()) // Ồ
            '\u1ED3' -> byteArrayOf(0xE5.toByte()) // ồ
            '\u1ED4' -> byteArrayOf(0xA4.toByte(), 0xE6.toByte()) // Ổ
            '\u1ED5' -> byteArrayOf(0xE6.toByte()) // ổ
            '\u1ED6' -> byteArrayOf(0xA4.toByte(), 0xE7.toByte()) // Ỗ
            '\u1ED7' -> byteArrayOf(0xE7.toByte()) // ỗ
            '\u1ED8' -> byteArrayOf(0xA4.toByte(), 0xE9.toByte()) // Ộ
            '\u1ED9' -> byteArrayOf(0xE9.toByte()) // ộ
            '\u1EDA' -> byteArrayOf(0xA5.toByte(), 0xED.toByte()) // Ớ
            '\u1EDB' -> byteArrayOf(0xED.toByte()) // ớ
            '\u1EDC' -> byteArrayOf(0xA5.toByte(), 0xEA.toByte()) // Ờ
            '\u1EDD' -> byteArrayOf(0xEA.toByte()) // ờ
            '\u1EDE' -> byteArrayOf(0xA5.toByte(), 0xEB.toByte()) // Ở
            '\u1EDF' -> byteArrayOf(0xEB.toByte()) // ở
            '\u1EE0' -> byteArrayOf(0xA5.toByte(), 0xEC.toByte()) // Ỡ
            '\u1EE1' -> byteArrayOf(0xEC.toByte()) // ỡ
            '\u1EE2' -> byteArrayOf(0xA5.toByte(), 0xEE.toByte()) // Ợ
            '\u1EE3' -> byteArrayOf(0xEE.toByte()) // ợ
            '\u1EE4' -> byteArrayOf(0x55, 0xF4.toByte()) // Ụ
            '\u1EE5' -> byteArrayOf(0xF4.toByte()) // ụ
            '\u1EE6' -> byteArrayOf(0x55, 0xF1.toByte()) // Ủ
            '\u1EE7' -> byteArrayOf(0xF1.toByte()) // ủ
            '\u1EE8' -> byteArrayOf(0xA6.toByte(), 0xF8.toByte()) // Ứ
            '\u1EE9' -> byteArrayOf(0xF8.toByte()) // ứ
            '\u1EEA' -> byteArrayOf(0xA6.toByte(), 0xF5.toByte()) // Ừ
            '\u1EEB' -> byteArrayOf(0xF5.toByte()) // ừ
            '\u1EEC' -> byteArrayOf(0xA6.toByte(), 0xF6.toByte()) // Ử
            '\u1EED' -> byteArrayOf(0xF6.toByte()) // ử
            '\u1EEE' -> byteArrayOf(0xA6.toByte(), 0xF7.toByte()) // Ữ
            '\u1EEF' -> byteArrayOf(0xF7.toByte()) // ữ
            '\u1EF0' -> byteArrayOf(0xA6.toByte(), 0xF9.toByte()) // Ự
            '\u1EF1' -> byteArrayOf(0xF9.toByte()) // ự
            '\u1EF2' -> byteArrayOf(0x59, 0xFA.toByte()) // Ỳ
            '\u1EF3' -> byteArrayOf(0xFA.toByte()) // ỳ
            '\u1EF4' -> byteArrayOf(0x59, 0xFE.toByte()) // Ỵ
            '\u1EF5' -> byteArrayOf(0xFE.toByte()) // ỵ
            '\u1EF6' -> byteArrayOf(0x59, 0xFB.toByte()) // Ỷ
            '\u1EF7' -> byteArrayOf(0xFB.toByte()) // ỷ
            '\u1EF8' -> byteArrayOf(0x59, 0xFC.toByte()) // Ỹ
            '\u1EF9' -> byteArrayOf(0xFC.toByte()) // ỹ
            else -> null
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
