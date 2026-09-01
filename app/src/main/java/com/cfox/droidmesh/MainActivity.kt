package com.cfox.droidmesh

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.cfox.droidmesh.databinding.ActivityMainBinding
import com.cfox.droidmesh.service.UpdaterForegroundService
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.Logger

/**
 * DroidMesh's entire UI is the embedded Web UI (assets/web/index.html), served locally by
 * LocalHttpServer. MainActivity is a thin shell around it (UI-BEHAVE-005): it starts the
 * foreground service that owns that server, applies immersive fullscreen mode, and loads the
 * Web UI into a WebView. It registers no addJavascriptInterface bridge (UI-BEHAVE-006) — every
 * capability the Web UI needs from the native layer is a REST endpoint on LocalHttpServer
 * instead (rest-api.md API-BEHAVE-016/017), so there is no second UI surface to keep in sync.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyImmersiveMode()

        // Starts (or confirms running) the foreground service that owns LocalHttpServer — the
        // WebView below has nothing to load until this server is listening.
        UpdaterForegroundService.startService(this)

        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        binding.webView.webViewClient = ShellWebViewClient()
        loadWebUi()
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun loadWebUi() {
        val port = SettingsStore.getWebServerPort(this)
        binding.webView.loadUrl("http://127.0.0.1:$port/")
    }

    /**
     * The foreground service's HTTP server binds asynchronously relative to onCreate returning,
     * so the first load can race it. Retry once on a main-frame load failure rather than leaving
     * the shell permanently blank.
     */
    private inner class ShellWebViewClient : WebViewClient() {
        private var retried = false

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (!request.isForMainFrame) return
            if (retried) {
                Logger.e("WebView failed to load Web UI after retry: ${error.description}")
                return
            }
            retried = true
            Logger.w("WebView load failed (${error.description}), retrying in 750ms")
            view.postDelayed({ loadWebUi() }, 750)
        }
    }

    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
}
