package co.moodyteas.squarebridge

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class KDSActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private var connectivityManager: ConnectivityManager? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Updater.check(this)

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
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    offlineView.visibility = View.GONE
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) offlineView.visibility = View.VISIBLE
                }
            }
            wv.loadUrl(BuildConfig.KDS_URL)
        }

        offlineView = TextView(this).apply {
            text = "Waiting for KDS\u2026"
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
                if (webView.canGoBack()) webView.goBack() else webView.reload()
            }
        })

        hideSystemUi()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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
