package com.example.mamba

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.koushikdutta.async.http.WebSocket
import com.koushikdutta.async.http.server.AsyncHttpServer
import kotlinx.serialization.json.*
import android.Manifest
import android.location.GnssStatus
import android.location.LocationListener
import android.location.OnNmeaMessageListener
import android.os.BatteryManager
import android.os.Handler
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow

fun Double.truncate(decimals: Int): Double {
    val factor = 10.0.pow(decimals)
    return (this * factor).toInt() / factor
}

class ServerService : Service() {

    private val server = AsyncHttpServer()
    private var lat: Double = 0.0
    private var lon: Double = 0.0
    private var alt: Double = 0.0
    private val TAG = "ServerService"

    // 在类的顶部声明变量：
    private lateinit var locationManager: LocationManager
    private lateinit var locationListener: LocationListener
    private var batteryLevel: Int = 0          // 手机电量 (0-100)
    private var satellitesTotal: Int = 0       // 可见卫星总数
    private var satellitesUsed: Int = 0        // 实际参与定位的卫星数
    private var fixType: Int = 0               // GPS Fix Type (0=无效, 1=单点定位, 2=差分定位/RTK)

    // 监听器声明
    private lateinit var gnssStatusCallback: GnssStatus.Callback
    private lateinit var nmeaListener: OnNmeaMessageListener
    private var wsClientList = CopyOnWriteArrayList<WebSocket>()

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
                    addJsonObject {
                        put("name", "经度")
                        put("key", "lon")
                        put("collapse", false)
                    }
                    addJsonObject {
                        put("name", "纬度")
                        put("key", "lat")
                        put("collapse", false)
                    }
                    addJsonObject {
                        put("name", "电池电量")
                        put("key", "battery_level")
                        put("collapse", false)
                    }
                    addJsonObject {
                        put("name", "GPS星数")
                        put("key", "gps_nsats")
                        put("collapse", false)
                    }
                    addJsonObject {
                        put("name", "GPS fix_type")
                        put("key", "gps_fix_type")
                        put("collapse", false)
                    }
                }
                putJsonArray("button") {
                    addJsonObject {
                        put("key", "get_gps")
                        put("name", "获取gps")
                        putJsonObject("target") {
                            put("config_type", "toast")
                            put("method", "GET")
                            put("url", "/get_gps")
                        }
                    }
                    addJsonObject {
                        put("key", "prearms")
                        put("name", "起飞检查")
                        putJsonObject("target") {
                            put("config_type", "toast")
                            put("method", "GET")
                            put("url", "/prearms")
                        }
                    }
                    addJsonObject {
                        put("key", "takeoff")
                        put("name", "起飞")
                        putJsonObject("target") {
                            put("config_type", "toast")
                            put("method", "POST")
                            put("url", "/takeoff")
                        }
                    }
                }
            }
            Log.d(TAG, String.format("get_page_config %s", pageConfig.toString()))
            response.send(pageConfig.toString())
        }

        server.get("/get_gps") { request, response ->
            val gpsData = buildJsonObject {
                put("status", "success")
                putJsonArray("msg") {
                    add(lon)
                    add(lat)
                    add(alt)
                }
            }
            Log.d(TAG, String.format("get_gps %s", gpsData.toString()))
            response.send(gpsData.toString())
        }

        server.get("/prearms") { request, response ->
            val data = buildJsonObject {
                put("status", "success")
                putJsonObject("msg") {
                    put("arm", true)
                }
            }
            response.send(data.toString())
        }

        server.post("/takeoff") { request, response ->
            val data = buildJsonObject {
                put("status", "success")
                put("msg", "OK")
            }
            response.send(data.toString())
        }

        server.post("/set_waypoint") { request, response ->
            val data = buildJsonObject {
                put("status", "success")
                put("msg", "OK")
            }
            response.send(data.toString())
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
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // 1. 创建位置监听器
        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                Log.d(
                    TAG,
                    "原生定位获取成功: 纬度=${location.latitude}, 经度=${location.longitude}"
                )

                lat = location.latitude
                lon = location.longitude
                alt = location.altitude

                // 获取电量非常简单，直接调用系统服务
                val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
                batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

                // 发送给 WebSocket 客户端
                val size = wsClientList.size
                val realFixType = if (fixType == 0) 0 else fixType + 2
                var data = buildJsonObject {
                    put("type", "state")
                    put("connect_num", size)
                    put("lat", lat.truncate(5))
                    put("lon", lon.truncate(5))
                    put("battery_level", batteryLevel)
                    put("gps_nsats", satellitesUsed)
                    put("gps_fix_type", realFixType)
                }.toString()

                for (ws in wsClientList) {
                    ws.send(data)
                }
            }

            // 下面这三个方法在较新的 Android 版本中是可选的，但为了兼容老版本建议保留空实现
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {
                Log.d(TAG, "定位服务开启: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                Log.d(TAG, "定位服务关闭: $provider")
            }
        }
        // 2. 检查权限 (必须检查，否则 IDE 会报错，代码也会崩溃)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
            && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "没有定位权限！请先在 Activity 中动态申请权限。")
            return
        }
        try {
            // 3. 注册定位监听 (这里同时注册 GPS 和 网络定位，保证室内外都有回调)
            val minTimeMs = 2000L // 最小更新间隔 2000 毫秒 (2秒)
            val minDistanceM = 0f // 最小更新距离 0 米 (只要时间到了就更新)
            // 尝试使用网络定位 (基站/Wi-Fi，速度快，室内可用，精度低)
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, minTimeMs, minDistanceM, locationListener
                )
                Log.d(TAG, "已启动 NETWORK_PROVIDER 定位")
            }


            // 注册 GNSS 状态监听器 (Android 7.0 / API 24 引入)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                gnssStatusCallback = object : GnssStatus.Callback() {
                    override fun onSatelliteStatusChanged(status: GnssStatus) {
                        satellitesTotal = status.satelliteCount
                        var used = 0
                        for (i in 0 until satellitesTotal) {
                            if (status.usedInFix(i)) used++
                        }
                        satellitesUsed = used
                        Log.d(TAG, "可见卫星: $satellitesTotal, 参与定位: $satellitesUsed")
                    }
                }
                // 权限前面已经查过了，这里直接注册
                try {
                    locationManager.registerGnssStatusCallback(
                        gnssStatusCallback,
                        Handler(Looper.getMainLooper())
                    )
                } catch (e: SecurityException) {
                    e.printStackTrace()
                }
            }
            // 尝试使用卫星定位 (GPS，速度慢，仅室外可用，精度极高)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, minTimeMs, minDistanceM, locationListener
                )
                Log.d(TAG, "已启动 GPS_PROVIDER 定位")
            }
        } catch (e: Exception) {
            Log.e(TAG, "请求定位失败: ${e.message}")
        }
        // 注册 NMEA 监听器 (Android 7.0 / API 24 引入)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            nmeaListener = OnNmeaMessageListener { message, timestamp ->
                // 抓取 GGA 报文 (通常是 $GPGGA, $GLGGA, $GNGGA)
                if (message.startsWith("\$GPGGA") || message.startsWith("\$GNGGA")) {
                    val parts = message.split(",")
                    // GGA 报文用逗号分隔，第6个索引位置代表 Fix Quality
                    if (parts.size > 6 && parts[6].isNotEmpty()) {
                        val newFixType = parts[6].toIntOrNull() ?: 0
                        if (fixType != newFixType) {
                            fixType = newFixType
                            Log.d(TAG, "GPS FixType 更新为: $fixType")
                        }
                    }
                }
            }
            try {
                locationManager.addNmeaListener(nmeaListener, Handler(Looper.getMainLooper()))
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server.stop()

        // 停止原生定位
        if (::locationManager.isInitialized && ::locationListener.isInitialized) {
            locationManager.removeUpdates(locationListener)
            Log.d(TAG, "已停止定位监听")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}