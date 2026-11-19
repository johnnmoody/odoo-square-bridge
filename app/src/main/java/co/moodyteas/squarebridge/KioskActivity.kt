package co.moodyteas.squarebridge

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.webkit.HttpAuthHandler
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class KioskActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    // Prevents repeat Square battery dialog within a single app session.
    private var squareBatteryPrompted = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                if (offlineView.visibility == View.VISIBLE) {
                    offlineView.visibility = View.GONE
                    webView.reload()
                }
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun launchIntent(url: String) {
            runOnUiThread {
                // Only the configured POS origin may launch intents. Any other page
                // the WebView ends up on gets no access to the bridge.
                if (Uri.parse(webView.url ?: "").host != Uri.parse(BuildConfig.POS_URL).host) return@runOnUiThread
                try {
                    // intent:// URIs must be parsed with parseUri so Android
                    // correctly routes them to the target activity (BridgeActivity).
                    // Using ACTION_VIEW directly does not work for intent:// scheme.
                    val intent = if (url.startsWith("intent://")) {
                        Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    } else {
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this@KioskActivity, "Launch failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkBatteryOptimization()
    }

    // Requests battery optimization exemption for this app (shows system dialog once, persists
    // across reboots). Then requests the same for the Square app so the card reader stays
    // connected between transactions. Each dialog appears at most once per app session.
    private fun checkBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        when {
            !pm.isIgnoringBatteryOptimizations(packageName) -> {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {}
            }
            !squareBatteryPrompted && !pm.isIgnoringBatteryOptimizations("com.squareup") -> {
                squareBatteryPrompted = true
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:com.squareup")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Updater.check(this)

        // Keep this setting written so landscape stays respected after reboots
        try { Settings.Global.putInt(contentResolver, "ignore_orientation_request", 0) } catch (_: Exception) {}

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = RelativeLayout(this)
        val lp = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        webView = WebView(this).also { wv ->
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.setSupportZoom(false)
            wv.addJavascriptInterface(AndroidBridge(), "Android")
            wv.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                    Toast.makeText(this@KioskActivity, message, Toast.LENGTH_LONG).show()
                    result.confirm()
                    return true
                }

                // Handle requestFullscreen() from the Odoo POS web app
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    fullscreenView?.let { (root.parent as? android.view.ViewGroup)?.removeView(it) }
                    fullscreenView = view
                    fullscreenCallback = callback
                    val flp = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    root.addView(view, flp)
                    webView.visibility = View.GONE
                    hideSystemUi()
                }

                override fun onHideCustomView() {
                    fullscreenView?.let { root.removeView(it) }
                    fullscreenView = null
                    fullscreenCallback?.onCustomViewHidden()
                    fullscreenCallback = null
                    webView.visibility = View.VISIBLE
                    hideSystemUi()
                }
            }
            wv.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return false
                    if (scheme == "http" || scheme == "https") return false
                    return try { startActivity(Intent(Intent.ACTION_VIEW, request.url)); true } catch (_: Exception) { true }
                }
                override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                    if (BuildConfig.AUTH_USER.isNotEmpty()) handler.proceed(BuildConfig.AUTH_USER, BuildConfig.AUTH_PASS)
                    else handler.cancel()
                }
                override fun onPageFinished(view: WebView, url: String) {
                    offlineView.visibility = View.GONE
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) offlineView.visibility = View.VISIBLE
                }
            }
            wv.loadUrl(BuildConfig.POS_URL)
        }

        offlineView = TextView(this).apply {
            text = "Reconnecting to POS\u2026"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1a1a2e.toInt())
            visibility = View.GONE
        }

        root.addView(webView, lp)
        root.addView(offlineView, lp)
        setContentView(root)

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
        connectivityManager?.registerNetworkCallback(req, networkCallback)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreenView != null) {
                    onHideCustomView()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    webView.reload()
                }
            }
        })

        hideSystemUi()
    }

    private fun onHideCustomView() {
        fullscreenView?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
        webView.visibility = View.VISIBLE
        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) { webView.reload(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager?.unregisterNetworkCallback(networkCallback)
    }

    private fun hideSystemUi() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
