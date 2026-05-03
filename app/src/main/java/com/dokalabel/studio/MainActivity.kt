package com.dokalabel.studio

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.dokalabel.studio.USB_PERMISSION"
        // All known Xprinter vendor IDs
        private val XPRINTER_VENDOR_IDS = setOf(0x2D37, 0x1504, 0x0FE6, 0x2727, 0x154F)
    }

    private lateinit var webView: WebView
    lateinit var bridge: UsbPrintBridge

    /** Saved while we wait for the user to grant USB permission. */
    private var pendingPrintData: ByteArray? = null

    // ── USB + permission broadcast receiver ───────────────────────────────────

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {

                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                    if (granted && device != null) {
                        pendingPrintData?.let { data ->
                            pendingPrintData = null
                            performUsbPrint(device, data)
                        }
                    } else {
                        bridge.sendCallback(false, "USB permission denied — tap Allow when prompted.")
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    bridge.sendPrinterStatus(true)
                    bridge.sendCallback(true, "Printer connected!")
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    bridge.sendPrinterStatus(false)
                }
            }
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // Needed so file:// page can reach file:// sub-resources (saved templates etc.)
                allowFileAccessFromFileURLs = true
                allowUniversalAccessFromFileURLs = true
                cacheMode = WebSettings.LOAD_DEFAULT
                mediaPlaybackRequiresUserGesture = false
                // Prevent font boosting on mobile
                textZoom = 100
            }
            // Prevent any link from leaving the WebView
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Let file:// and about: through; block everything else
                    val url = request.url.toString()
                    return !(url.startsWith("file://") || url.startsWith("about:"))
                }
            }
            webChromeClient = WebChromeClient()
        }

        // Inject the native bridge as window.AndroidBridge
        bridge = UsbPrintBridge(this, webView)
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        setContentView(webView)

        // Load the bundled label designer
        webView.loadUrl("file:///android_asset/index.html")

        // Register for USB events
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        webView.destroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    // ── USB printing ──────────────────────────────────────────────────────────

    /**
     * Entry point called by the JS bridge.
     * Finds an Xprinter on USB, requests permission if needed, then prints.
     */
    fun requestUsbAndPrint(data: ByteArray) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        // Find the first attached Xprinter
        val device = usbManager.deviceList.values.firstOrNull { it.vendorId in XPRINTER_VENDOR_IDS }

        if (device == null) {
            bridge.sendCallback(
                false,
                "Printer not found. Connect the USB OTG cable to the printer, then try again."
            )
            return
        }

        if (!usbManager.hasPermission(device)) {
            // Ask the user once — Android remembers the answer
            pendingPrintData = data
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT

            val permIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), flags)
            usbManager.requestPermission(device, permIntent)
            bridge.sendCallback(true, "Requesting USB permission — tap Allow…")
            return
        }

        performUsbPrint(device, data)
    }

    /**
     * Opens the USB device, finds the bulk-OUT endpoint, streams TSPL data,
     * then closes the connection.  Runs on a dedicated thread.
     */
    fun performUsbPrint(device: UsbDevice, data: ByteArray) {
        Thread {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(device)

            if (connection == null) {
                bridge.sendCallback(false, "Cannot open printer. Unplug and replug the USB OTG cable.")
                return@Thread
            }

            try {
                // ── Find bulk-OUT endpoint ────────────────────────────────────
                var targetInterfaceIndex = -1
                var targetEndpointIndex  = -1

                outer@ for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_OUT &&
                            ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            targetInterfaceIndex = i
                            targetEndpointIndex  = e
                            break@outer
                        }
                    }
                }

                if (targetInterfaceIndex < 0) {
                    bridge.sendCallback(false, "No bulk-OUT endpoint found. Is this the Xprinter XP-470B?")
                    return@Thread
                }

                val intf     = device.getInterface(targetInterfaceIndex)
                val endpoint = intf.getEndpoint(targetEndpointIndex)

                // Claim the interface (force=true releases from any kernel driver)
                if (!connection.claimInterface(intf, true)) {
                    bridge.sendCallback(
                        false,
                        "Cannot claim USB interface. Close any other printer app and try again."
                    )
                    return@Thread
                }

                // ── Stream TSPL data in 16 KB chunks ─────────────────────────
                val CHUNK = 16_384
                var offset = 0
                var success = true

                while (offset < data.size) {
                    val end   = minOf(offset + CHUNK, data.size)
                    val chunk = data.copyOfRange(offset, end)
                    val sent  = connection.bulkTransfer(endpoint, chunk, chunk.size, 5_000 /*ms*/)
                    if (sent < 0) { success = false; break }
                    offset += sent
                }

                connection.releaseInterface(intf)

                if (success) bridge.sendCallback(true, "Label printed successfully! ✅")
                else bridge.sendCallback(false, "USB transfer error — check printer is on and paper is loaded.")

            } catch (e: Exception) {
                bridge.sendCallback(false, "Print error: ${e.message}")
            } finally {
                try { connection.close() } catch (_: Exception) {}
            }
        }.start()
    }
}
