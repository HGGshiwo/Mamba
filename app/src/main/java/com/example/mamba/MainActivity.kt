package com.example.mamba // 替换为你的包名

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.mamba.ServerService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val context = LocalContext.current
    // 记录权限是否已授予
    var permissionsGranted by remember { mutableStateOf(false) }
    // 记录服务是否已启动（给一点延迟，确保服务起好了 WebView 再去连）
    var serverReady by remember { mutableStateOf(false) }

    // Compose 中的权限请求启动器
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val locationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locationGranted) {
            permissionsGranted = true
            // 权限拿到后，启动后台服务
            val intent = Intent(context, ServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    // 界面刚加载时，自动发起权限请求
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    // 监听权限状态，拿到权限后延迟一小会儿（等服务启动），再加载网页
    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            delay(1000) // 延迟1秒，确保 8000 端口已经 listen 成功，防止网页白屏报错
            serverReady = true
        }
    }

    // UI 渲染逻辑
    if (!permissionsGranted) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text("请授予位置和通知权限以启动本地服务器")
        }
    } else if (!serverReady) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator() // 显示一个加载圈
        }
    } else {
        // 权限有了，服务也启动了，加载 8000 端口的网页！
        LocalWebViewScreen()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LocalWebViewScreen() {
    // 使用 AndroidView 嵌套传统的 WebView
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                WebView.setWebContentsDebuggingEnabled(true)
                // 必须开启 JS，否则 HTML 里的 WebSocket 脚本跑不起来
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                // 限制在 App 内部打开网页，防止跳到系统浏览器
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        Log.d("WebView", "onPageStarted: $url")
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d("WebView", "onPageFinished: $url")
                    }
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        Log.e("WebView", "onReceivedError url=${request?.url} err=${error?.description}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            "WebViewConsole",
                            "${consoleMessage.message()} -- line ${consoleMessage.lineNumber()} -- ${consoleMessage.sourceId()}"
                        )
                        return true
                    }
                }

                // 拉取本地 8000 端口的内容
                loadUrl("http://127.0.0.1:8000/")
            }
        }
    )
}