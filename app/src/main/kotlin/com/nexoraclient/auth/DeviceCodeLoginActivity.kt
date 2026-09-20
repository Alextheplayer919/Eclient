package com.rubidiumclient.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

class DeviceCodeLoginActivity : ComponentActivity() {

    companion object {
        private const val CLIENT_ID    = "0000000048183522"
        private const val REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf"
        private const val SCOPE        = "service::user.auth.xboxlive.com::MBI_SSL"

        private val AUTH_URL = buildString {
            append("https://login.live.com/oauth20_authorize.srf")
            append("?client_id=$CLIENT_ID")
            append("&response_type=code")
            append("&redirect_uri=${android.net.Uri.encode(REDIRECT_URI)}")
            append("&scope=${android.net.Uri.encode(SCOPE)}")
            append("&display=touch")
            append("&locale=en")
        }
    }

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private var codeExchanged = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = RelativeLayout(this).apply {
            setBackgroundColor(0xFF0D0D14.toInt())
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = View.generateViewId()
            isIndeterminate = true
            indeterminateDrawable?.setTint(0xFF9B59B6.toInt())
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT, 8
            ).apply { addRule(RelativeLayout.ALIGN_PARENT_TOP) }
        }

        webView = WebView(this).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply { addRule(RelativeLayout.BELOW, progressBar.id) }

            settings.apply {
                javaScriptEnabled    = true
                domStorageEnabled    = true
                loadWithOverviewMode = true
                useWideViewPort      = true
                setSupportZoom(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    progressBar.visibility = View.VISIBLE
                    interceptRedirect(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    progressBar.visibility = View.GONE
                    interceptRedirect(url)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    interceptRedirect(request.url.toString())
                    return false
                }
            }
        }

        root.addView(progressBar)
        root.addView(webView)
        setContentView(root)

        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }

        webView.loadUrl(AUTH_URL)
    }

    private fun interceptRedirect(url: String) {
        if (codeExchanged) return
        if (!url.startsWith(REDIRECT_URI)) return

        val uri  = android.net.Uri.parse(url)
        val code = uri.getQueryParameter("code")
        val err  = uri.getQueryParameter("error")

        when {
            !code.isNullOrBlank() -> {
                codeExchanged = true
                Toast.makeText(this, "Code received, connecting…", Toast.LENGTH_SHORT).show()
                MicrosoftAuthManager.exchangeCodeForToken(code)
                finish()
            }
            !err.isNullOrBlank() -> {
                Toast.makeText(this, "Sign-in failed: $err", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }
}
