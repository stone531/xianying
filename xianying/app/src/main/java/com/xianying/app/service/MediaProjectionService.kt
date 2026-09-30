package com.xianying.app.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.xianying.app.detector.FrameDiffDetector
import com.xianying.app.model.TargetApp
import com.xianying.app.runtime.Runtime
import com.xianying.app.session.Status

/**
 * 画面采样服务——抖音"仅条数"模式的眼睛。
 *
 * 背景：抖音信息流不发 TYPE_VIEW_SCROLLED（2026-09-30 实测），无障碍层面数不了条数；
 * 本服务走设计文档的备选路线：MediaProjection 低清镜像 + 帧差比对。
 *
 * 隐私与开销承诺（与产品红线一致）：
 *  - 只截 144×256 的低清画面算亮度网格，**不保存任何画面/文件**，用完即弃
 *  - 约 2.5 帧/秒采样，且仅在"计时中 + 条数模式"才真正比对，其余时间只清基线
 *  - 状态栏会有系统录屏图标（系统规定，无法隐藏），属已知代价
 *
 * 生命周期：主界面"④ 画面识别"授权后 start()；总开关关闭或系统收回授权时停止。
 */
class MediaProjectionService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var sampleThread: HandlerThread? = null
    private val detector = FrameDiffDetector(GRID_W, GRID_H, changedRatioThreshold = RATIO_THRESHOLD).apply {
        sampleIntervalMs = SAMPLE_INTERVAL_MS
    }
    private var lastSampleAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            Log.e(TAG, "invalid projection grant, stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14+ 规定：必须先 startForeground（带 mediaProjection 类型）才能拿投影
        startForegroundWith()
        running = true

        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // API 36 起 getMediaProjection 返回可空：授权数据失效时为 null
            val mp = mpm.getMediaProjection(resultCode, resultData) ?: run {
                Log.e(TAG, "getMediaProjection null (grant expired?)")
                stopSelf()
                return START_NOT_STICKY
            }
            projection = mp
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    // 用户从状态栏点"停止投放"或系统收回授权
                    Log.w(TAG, "projection stopped by system/user")
                    stopSelf()
                }
            }, handler())

            val r = ImageReader.newInstance(CAP_W, CAP_H, android.graphics.PixelFormat.RGBA_8888, 2)
            reader = r
            r.setOnImageAvailableListener({ onImage(it) }, handler())
            // 注意 API 36 签名：(name, w, h, dpi, flags, surface, callback, handler)
            // —— flags 在 surface 之前，与网上老示例相反
            display = mp.createVirtualDisplay(
                "XianyingFrame", CAP_W, CAP_H, CAP_DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler(),
            )
            Log.i(TAG, "sampling started ${CAP_W}x$CAP_H grid ${GRID_W}x$GRID_H")
        } catch (e: Exception) {
            // fail-open：画面识别故障不影响时长模式，静默退场
            Log.e(TAG, "projection setup failed", e)
            stopSelf()
        }
        // 不粘滞：被杀后不自动重启，用户下次进主界面会看到④未开启
        return START_NOT_STICKY
    }

    /** 每帧回调：先判该不该比对该（gating），不该就清基线直接丢帧。 */
    private fun onImage(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        try {
            val snap = Runtime.sessionManager.snapshot()
            // 帧差只服务抖音（它不发滚动事件）；快手/B站走无障碍滚动事件直连。
            // 若不加此门：④开着刷B站会帧差+滚动双通道重复计数（2026-09-30 实测发现）。
            val inDouyin = TargetApp.fromPackage(Runtime.lastTargetPkg) == TargetApp.DOUYIN
            val gated = snap.status == Status.TIMING &&
                (snap.config?.maxVideos ?: 0) > 0 && inDouyin
            if (!gated) {
                detector.reset()      // 离开目标/非条数模式：清基线，防回来误报
                return
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastSampleAt < SAMPLE_INTERVAL_MS) return
            lastSampleAt = now
            val luma = downsample(image) ?: return
            val isSwitch = detector.feed(luma)
            // 调参期诊断：真实画面下"播放中/切换"各自的 ratio 分布
            Log.d(TAG, "ratio=%.3f switch=%b".format(detector.lastChangedRatio, isSwitch))
            if (isSwitch) {
                Log.d(TAG, "video switch detected -> onVideoChanged")
                Runtime.sessionManager.onVideoChanged()
            }
        } finally {
            image.close()
        }
    }

    /**
     * RGBA 图像 → GRID_W×GRID_H 亮度网格（每格 = 该区域像素平均亮度 0~255）。
     * 亮度用 ITU-R BT.601 权重（人眼对绿最敏感），与检测器阈值同一量纲。
     */
    private fun downsample(img: Image): IntArray? {
        return try {
            val plane = img.planes[0]
            val buf = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val out = IntArray(GRID_W * GRID_H)
            for (gy in 0 until GRID_H) {
                val y0 = gy * CAP_H / GRID_H
                val y1 = (gy + 1) * CAP_H / GRID_H
                for (gx in 0 until GRID_W) {
                    val x0 = gx * CAP_W / GRID_W
                    val x1 = (gx + 1) * CAP_W / GRID_W
                    var sum = 0L
                    var n = 0
                    for (y in y0 until y1) {
                        var idx = y * rowStride + x0 * pixelStride
                        for (x in x0 until x1) {
                            val r = buf.get(idx).toInt() and 0xFF
                            val g = buf.get(idx + 1).toInt() and 0xFF
                            val b = buf.get(idx + 2).toInt() and 0xFF
                            sum += (r * 299 + g * 587 + b * 114) / 1000
                            n++
                            idx += pixelStride
                        }
                    }
                    out[gy * GRID_W + gx] = (sum / n).toInt()
                }
            }
            out
        } catch (e: Exception) {
            Log.e(TAG, "downsample failed", e)
            null
        }
    }

    private fun startForegroundWith() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "画面识别", NotificationManager.IMPORTANCE_LOW).apply {
                description = "仅条数模式的画面采样（低清，不保存画面）"
            }
        )
        val noti = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("限映 · 画面识别运行中")
            .setContentText("低清采样比对切屏，不保存任何画面")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTI_ID, noti, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTI_ID, noti)
        }
    }

    override fun onDestroy() {
        running = false
        try { reader?.close() } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        sampleThread?.quitSafely()
        reader = null; display = null; projection = null; sampleThread = null
        Log.i(TAG, "sampling stopped")
        super.onDestroy()
    }

    /** 采样/解码放独立线程，不占主线程也不占无障碍线程。 */
    private fun handler(): Handler {
        var t = sampleThread
        if (t == null) {
            t = HandlerThread("XianyingFrame").also { it.start() }
            sampleThread = t
        }
        return Handler(t.looper)
    }

    companion object {
        private const val TAG = "XianyingFrame"

        /** 采样分辨率：够分辨"整屏换内容"即可，越小越省电。 */
        const val CAP_W = 144
        const val CAP_H = 256
        private const val CAP_DPI = 72

        /** 帧差网格（与检测器/单测同一规格）。 */
        const val GRID_W = 16
        const val GRID_H = 28

        /** 采样间隔：约 2.5 帧/秒。 */
        private const val SAMPLE_INTERVAL_MS = 400L

        /**
         * 整屏切换判定阈值。模拟器实测（2026-09-30，抖音视频解码为绿屏、仅文字区变化）：
         * 播放噪音 ratio ≤ 0.27，滑动切换峰值 0.35~0.77 → 取 0.35。
         * 真机视频内容整屏替换，信号应更强，阈值需真机重校（TESTING.md 必测项）。
         */
        private const val RATIO_THRESHOLD = 0.35

        private const val CHANNEL_ID = "xianying_frame"
        private const val NOTI_ID = 3
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        /** 供主界面显示授权状态。 */
        @Volatile var running = false

        /** 授权成功后由主界面调用（resultCode/data 来自 createScreenCaptureIntent 回执）。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, MediaProjectionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MediaProjectionService::class.java))
        }
    }
}
