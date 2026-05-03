package com.dokalabel.studio

import android.hardware.usb.UsbManager
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView

/**
 * JavaScript interface injected as window.AndroidBridge.
 * All @JavascriptInterface methods run on a background thread — use
 * activity.runOnUiThread{} for anything touching the UI or WebView.
 */
class UsbPrintBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {

    /** Called by JS: AndroidBridge.printTSPL(base64EncodedTSPL) */
    @JavascriptInterface
    fun printTSPL(base64Data: String) {
        try {
            val data = Base64.decode(base64Data, Base64.DEFAULT)
            activity.runOnUiThread {
                activity.requestUsbAndPrint(data)
            }
        } catch (e: Exception) {
            sendCallback(false, "Data error: ${e.message}")
        }
    }

    /** Called by JS to detect it's inside the Android app (not a browser). */
    @JavascriptInterface
    fun isAndroidApp(): String = "true"

    /**
     * Returns a JSON array of connected Xprinter device names.
     * e.g. ["XP-470B"]  or  []
     */
    @JavascriptInterface
    fun getPrinterList(): String {
        val usbManager = activity.getSystemService(android.content.Context.USB_SERVICE) as UsbManager
        val printers = usbManager.deviceList.values.filter { dev ->
            dev.vendorId in listOf(0x2D37, 0x1504, 0x0FE6, 0x2727, 0x154F)
        }
        return if (printers.isEmpty()) "[]"
        else "[${printers.joinToString(",") { "\"${it.productName ?: "Xprinter"}\"" }}]"
    }

    // ── Callbacks → JavaScript ────────────────────────────────────────────────

    /** Fire window.onAndroidPrintResult(success, message) in the WebView. */
    fun sendCallback(success: Boolean, message: String) {
        val safe = message
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", " ")
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.onAndroidPrintResult && window.onAndroidPrintResult($success,'$safe');",
                null
            )
        }
    }

    /** Fire window.onAndroidPrinterStatus(connected) in the WebView. */
    fun sendPrinterStatus(connected: Boolean) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "window.onAndroidPrinterStatus && window.onAndroidPrinterStatus($connected);",
                null
            )
        }
    }
}
