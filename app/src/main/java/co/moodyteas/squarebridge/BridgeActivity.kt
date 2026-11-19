package co.moodyteas.squarebridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class BridgeActivity : AppCompatActivity() {

    private var callbackUrl = ""
    private var token = ""
    private var launchTime = 0L

    private val squareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val errorCode = data?.getStringExtra("com.squareup.register.ERROR_CODE") ?: ""
        val errorDesc = data?.getStringExtra("com.squareup.register.ERROR_DESCRIPTION") ?: ""
        val extras = data?.extras?.keySet()?.joinToString { "$it=${data.extras?.get(it)}" } ?: "null"
        val elapsed = System.currentTimeMillis() - launchTime
        Log.w(TAG, "result: resultCode=${result.resultCode} errorCode=$errorCode errorDesc=$errorDesc elapsed=${elapsed}ms extras=$extras")

        val status = if (result.resultCode == RESULT_OK) "ok" else "failed"
        sendCallback(status, errorCode)

        // If Square returned within 4 seconds with a reader error, the reader was
        // disconnected. Show an alert so staff know to reconnect before retrying.
        if (status == "failed" && elapsed < 4000 &&
            (errorCode.contains("READER", ignoreCase = true) || errorCode.isEmpty())
        ) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Square Reader Disconnected")
                .setMessage("The card reader is not connected to Square.\n\nOpen the Square app and reconnect the reader, then try the payment again.")
                .setPositiveButton("Open Square") { _, _ ->
                    try {
                        val intent = packageManager.getLaunchIntentForPackage("com.squareup")
                        if (intent != null) startActivity(intent)
                    } catch (_: Exception) {}
                    finish()
                }
                .setNegativeButton("Dismiss") { _, _ -> finish() }
                .setOnDismissListener { finish() }
                .show()
        } else {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.data ?: return finish()
        val amount = uri.getQueryParameter("amount")?.toLongOrNull() ?: return finish()
        val currency = uri.getQueryParameter("currency") ?: "USD"
        token = uri.getQueryParameter("token") ?: ""
        callbackUrl = uri.getQueryParameter("callback") ?: ""
        val locationId = uri.getQueryParameter("location_id") ?: ""
        val clientId = uri.getQueryParameter("client_id") ?: ""

        Log.w(TAG, "onCreate: amount=$amount currency=$currency locationId=$locationId recreated=${savedInstanceState != null}")

        // If Android killed this process while Square was in the foreground (low-RAM
        // tablets do this), the activity is recreated and the pending result is
        // redelivered by the ActivityResult framework. Launching Square again here
        // would start a second charge, so just wait for the redelivered result.
        if (savedInstanceState != null) {
            launchTime = savedInstanceState.getLong(KEY_LAUNCH_TIME, 0L)
            return
        }

        val sq = Intent("com.squareup.register.action.CHARGE").apply {
            setPackage("com.squareup")
            putExtra("com.squareup.register.CLIENT_ID", clientId)
            putExtra("com.squareup.register.API_VERSION", "1.0")
            putExtra("com.squareup.register.TOTAL_AMOUNT", amount.toInt())
            putExtra("com.squareup.register.CURRENCY_CODE", currency)
            putStringArrayListExtra("com.squareup.register.TENDER_TYPES", arrayListOf(
                "com.squareup.register.TENDER_CARD",
                "com.squareup.register.TENDER_CASH",
                "com.squareup.register.TENDER_OTHER",
                "com.squareup.register.TENDER_CARD_ON_FILE"
            ))
            if (locationId.isNotEmpty()) putExtra("com.squareup.register.LOCATION_ID", locationId)
            if (token.isNotEmpty()) putExtra("com.squareup.register.REQUEST_METADATA", token)
            putExtra("com.squareup.register.AUTO_RETURN_TIMEOUT_MILLISECONDS", 3200)
        }

        try {
            Log.w(TAG, "launching Square")
            launchTime = System.currentTimeMillis()
            squareLauncher.launch(sq)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Square: ${e.message}")
            sendCallback("failed", "square_not_available")
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_LAUNCH_TIME, launchTime)
    }

    private fun sendCallback(status: String, errorCode: String) {
        if (callbackUrl.isEmpty()) return
        // The deep link is BROWSABLE, so any page can fire it. Only call back to
        // the configured POS host, never to a URL a stranger put in the link.
        val cb = Uri.parse(callbackUrl)
        if (cb.scheme != "https" || cb.host != Uri.parse(BuildConfig.POS_URL).host) {
            Log.w(TAG, "callback refused: host ${cb.host} is not the POS host")
            return
        }
        Thread {
            try {
                val url = Uri.parse(callbackUrl).buildUpon()
                    .appendQueryParameter("status", status)
                    .appendQueryParameter("state", token)
                    .also { if (errorCode.isNotEmpty()) it.appendQueryParameter("error_code", errorCode) }
                    .build()
                    .toString()
                val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.connect()
                conn.inputStream.close()
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Callback failed: ${e.message}")
            }
        }.start()
    }

    companion object {
        private const val TAG = "SquareBridge"
        private const val KEY_LAUNCH_TIME = "launchTime"
    }
}
