package com.example.mamba

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.koushikdutta.async.http.WebSocket
import com.koushikdutta.async.http.server.AsyncHttpServer
import kotlinx.serialization.json.*

class ServerService : Service() {

    private val server = AsyncHttpServer()
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var alt: Double = 0.0
    private val TAG = "ServerService"

    private var wsClientList: ArrayList<WebSocket> = ArrayList()

    override fun onCreate() {
        super.onCreate()
        startForegroundService() // 1. 启动通知栏保活
        startServer()            // 2. 启动服务器
        startLocationUpdates()   // 3. 开始获取经纬度
    }

    // --- 1. 通知保活逻辑 ---
    private fun startForegroundService() {
        val channelId = "ServerServiceChannel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "服务器后台运行通知", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("本地服务器运行中")
            .setContentText("正在监听请求并获取经纬度...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // 随便用个系统自带图标
            .build()

        // 启动前台服务，防止被杀
        startForeground(1, notification)
    }

    // --- 2. 服务器逻辑 (HTTP & WebSocket) ---
    private fun startServer() {
        // 响应 HTTP 请求
        server.get("/") { request, response ->
            try {
                Log.d(TAG, "HTTP GET /")
                // 从 assets/web 文件夹中读取 index.html
                val inputStream = assets.open("web/index.html")
                val htmlString = inputStream.bufferedReader().use { it.readText() }
                // 告诉浏览器这是网页
                response.send("text/html", htmlString)
                Log.d(TAG, "index.html returned ok")
            } catch (e: Exception) {
                response.code(404).send("找不到 index.html 文件！")
                Log.d(TAG, "index.html returned 404")
            }
        }
        server.get("/page_config") { request, response ->
            val pageConfig = buildJsonObject {
                putJsonArray("state") {
//                    addJsonObject {
//                        put("name", "服务状态")
//                        put("key", "status")
//                        put("collapse", false)
//                    }
                    addJsonObject {
                        put("name", "连接数量")
                        put("key", "connect_num")
                        put("collapse", false)
                    }
                }
            }
            Log.d(TAG, String.format("get_page_config %s", pageConfig.toString()))
            response.send(pageConfig.toString())
        }

        server.get("/gps") { request, response ->
            val gpsData = buildJsonObject {
                put("status", "OK")
                putJsonArray("msg") {
                    add(lon)
                    add(lat)
                    add(alt)
                }
            }
            Log.d(TAG, String.format("get_gps %s", gpsData.toString()))
            response.send(gpsData.toString())
        }

        server.get("/home/assets/.*") { request, response ->
            // 把 /home/assets/logo.png 转换成 logo.png
            var filePath = request.path.removePrefix("/home/assets/")
            filePath = "web/assets/${filePath}"
            Log.d("ServerService", "请求静态资源: $filePath")

            try {
                val inputStream = assets.open(filePath)
                val bytes = inputStream.readBytes()

                // 根据文件扩展名设置正确的 MIME 类型
                val mimeType = when {
                    filePath.endsWith(".png") -> "image/png"
                    filePath.endsWith(".jpg") || filePath.endsWith(".jpeg") -> "image/jpeg"
                    filePath.endsWith(".css") -> "text/css"
                    filePath.endsWith(".js") -> "application/javascript"
                    filePath.endsWith(".json") -> "application/json"
                    else -> "application/octet-stream"
                }

                response.send(mimeType, bytes)
            } catch (e: Exception) {
                Log.e("ServerService", "读取文件失败: $filePath, 错误: ${e.message}")
                response.code(404).send("文件不存在: $filePath")
            }
        }

        // 响应 WebSocket 请求
        server.websocket("/ws") { webSocket, request ->
            Log.d(TAG, "ws connect!")
            wsClientList.add(webSocket)
            webSocket.stringCallback = WebSocket.StringCallback { s ->
                println("收到客户端消息: $s")
            }
            webSocket.closedCallback = com.koushikdutta.async.callback.CompletedCallback {
                Log.d(TAG, "ws disconnect!")
                wsClientList.remove(webSocket)
            }
        }

        // 监听 8080 端口
        server.listen(8000)
    }

    // --- 3. 获取经纬度逻辑 ---
    private fun startLocationUpdates() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // 设置定位请求参数：高精度，每2秒获取一次
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    lat = location.latitude
                    lon = location.longitude
                    alt = location.altitude
                }
                val size = wsClientList.size
                for(ws in wsClientList){
                    ws.send(buildJsonObject {
                        put("type", "state")
                        put("connect_num", size)
                    }.toString())
                }
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest, locationCallback, Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            e.printStackTrace() // 注意：实际应用需确保已授权
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop() // 服务销毁时停止服务器
    }

    override fun onBind(intent: Intent?): IBinder? = null
}