package app.miya.offline

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream

/**
 * Miya 离线版主界面。
 *
 * 核心思路：
 *  1. 全部前端资源内嵌在 APK 的 assets/miya/ 下，由 WebViewAssetLoader
 *     以 https://appassets.androidplatform.net/ 这个固定虚拟域名提供。
 *     → 断网可用；Origin 永久固定，localStorage / IndexedDB / OPFS 永不丢。
 *  2. https 虚拟域名让 navigator.storage.getDirectory()（OPFS）能够启用，
 *     因为 OPFS 只在安全上下文可用，file:// 不行。
 *  3. 全屏：不用全屏主题（那会造成 resize 抖动和黑条），而是让内容真正铺满，
 *     再用 WindowInsets 把状态栏/导航栏那几条的高度"吃掉"（padding），
 *     让 WebView 的可视区正好等于安全区，不露黑底。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    companion object {
        private const val HOST = "appassets.androidplatform.net"
        private const val BASE_URL = "https://$HOST/miya/index.html"
        private const val ASSET_PREFIX = "/miya/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ---------- 全屏第一步：让窗口延伸到状态栏后面 ----------
        // 这样内容才不会因为状态栏留出空间而出现"上面一条黑"，
        // 真正的规避是靠下面的 insets padding。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ---------- 全屏第二步：隐藏系统栏 ----------
        hideSystemBars()

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            // 背景设成主题白，避免加载瞬间闪黑
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        setContentView(webView)

        configureWebView()

        webView.loadUrl(BASE_URL)

        // ---------- 全屏第三步：吃掉 insets，不露黑边 ----------
        // 把状态栏/导航栏/刘海的高度作为 padding 加给 WebView，
        // 这样 WebView 的内容区 = 真正可见区，底部不会留一条黑。
        webView.setOnApplyWindowInsetsListener { v, insets ->
            val bars = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    private fun configureWebView() {
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler(
                ASSET_PREFIX,
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage / sessionStorage
            databaseEnabled = true            // 老式 WebSQL，部分模块可能探测
            allowFileAccess = false           // 安全起见关闭，全部走 assetLoader
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = false
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            userAgentString = "$userAgentString MiyaOffline/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val resp = assetLoader.shouldInterceptRequest(request.url)
                if (resp != null) return resp
                // assets 里没有的远程资源（CDN 字体等），一律放行为空，
                // 避免 WebView 因断网长时间挂起拖慢首屏。
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                // 站内（虚拟域名）一律内部加载
                if (url.host == HOST) return false
                // 外链（如用户点开某个说明链接）交给系统浏览器
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()   // 从通知栏/多任务回来后重新隐藏
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }
}