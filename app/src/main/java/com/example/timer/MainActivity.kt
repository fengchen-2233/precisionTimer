package com.example.timer

import android.os.Bundle
import android.view.Choreographer
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    private lateinit var etTargetUrl: EditText
    private lateinit var etTargetTime: EditText
    private lateinit var btnStart: Button
    private lateinit var tvClock: TextView
    private lateinit var tvStatus: TextView

    private var isRunning = false
    private var targetTimestampMs: Long = 0
    private var timeOffsetUs: Long = 0
    private var syncJob: Job? = null
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etTargetUrl = findViewById(R.id.et_target_url)
        etTargetTime = findViewById(R.id.et_target_time)
        btnStart = findViewById(R.id.btn_start)
        tvClock = findViewById(R.id.tv_clock)
        tvStatus = findViewById(R.id.tv_status)

        etTargetUrl.setText("https://ntp1.aliyun.com")
        etTargetTime.setText("10:00:00")

        btnStart.setOnClickListener {
            if (!isRunning) startCountdown() else stopCountdown()
        }
    }

    private fun startCountdown() {
        val urlStr = etTargetUrl.text.toString().trim()
        val timeStr = etTargetTime.text.toString().trim()

        try {
            val parsedDate = timeFormat.parse(timeStr)
            if (parsedDate != null) {
                val targetCal = Calendar.getInstance().apply { time = parsedDate }
                val calendar = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, targetCal.get(Calendar.HOUR_OF_DAY))
                    set(Calendar.MINUTE, targetCal.get(Calendar.MINUTE))
                    set(Calendar.SECOND, targetCal.get(Calendar.SECOND))
                    set(Calendar.MILLISECOND, 0)
                }
                targetTimestampMs = calendar.timeInMillis
            }
        } catch (e: Exception) {
            tvStatus.text = "时间格式错误，请输入 HH:mm:ss"
            return
        }

        isRunning = true
        btnStart.text = "停止倒计时"
        etTargetUrl.isEnabled = false
        etTargetTime.isEnabled = false

        startNetworkSync(urlStr)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopCountdown() {
        isRunning = false
        syncJob?.cancel()
        Choreographer.getInstance().removeFrameCallback(this)

        btnStart.text = "开始校准与倒计时"
        etTargetUrl.isEnabled = true
        etTargetTime.isEnabled = true
        tvClock.text = "00:00:00.000"
        tvStatus.text = "已停止"
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRunning) return

        val alignedUs = (System.currentTimeMillis() * 1000) + timeOffsetUs
        val currentMs = alignedUs / 1000
        val currentMicrosRem = alignedUs % 1000
        val remainingMs = targetTimestampMs - currentMs

        if (remainingMs > 0) {
            val hours = remainingMs / (1000 * 60 * 60)
            val minutes = (remainingMs % (1000 * 60 * 60)) / (1000 * 60)
            val seconds = (remainingMs % (1000 * 60)) / 1000
            val millis = remainingMs % 1000

            tvClock.text = String.format("%02d:%02d:%02d.%03d\n[%03d μs]", 
                hours, minutes, seconds, millis, currentMicrosRem)
        } else {
            tvClock.text = "00:00:00.000\n[到达放号时刻！]"
        }

        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startNetworkSync(targetUrl: String) {
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            val formattedUrl = if (!targetUrl.startsWith("http")) "https://$targetUrl" else targetUrl

            while (isActive && isRunning) {
                try {
                    val t1 = System.currentTimeMillis()
                    val url = URL(formattedUrl)
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "HEAD"
                        connectTimeout = 2000
                        readTimeout = 2000
                        setRequestProperty("User-Agent", "Mozilla/5.0")
                    }

                    val serverDate = conn.date
                    val t4 = System.currentTimeMillis()
                    conn.disconnect()

                    if (serverDate > 0) {
                        val rtt = t4 - t1
                        val estimatedServerTime = serverDate + (rtt / 2)
                        val offsetMs = estimatedServerTime - t4

                        timeOffsetUs = offsetMs * 1000

                        withContext(Dispatchers.Main) {
                            tvStatus.text = "已对齐服务器 | RTT: ${rtt}ms | 相对偏差: ${offsetMs}ms"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        tvStatus.text = "连接失败: ${e.localizedMessage}"
                    }
                }
                delay(3000)
            }
        }
    }
}
