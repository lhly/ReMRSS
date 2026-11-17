package dev.remrss.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.remrss.platform.sdk.RearDataField
import dev.remrss.platform.sdk.RearDataSnapshot
import dev.remrss.platform.sdk.RearDataValue
import dev.remrss.platform.sdk.RearCastMode
import dev.remrss.platform.sdk.RearRegion
import dev.remrss.platform.sdk.RearCastRequest
import dev.remrss.platform.sdk.RearCastSessionHandle
import dev.remrss.platform.sdk.RearCastProgress
import dev.remrss.platform.sdk.RearCastProgressStage
import dev.remrss.platform.sdk.RearNotificationRequest
import dev.remrss.platform.sdk.RearNotificationSubmitStatus
import java.io.Closeable

internal fun castHandleAfterQuery(
    current: RearCastSessionHandle?,
    queried: RearCastSessionHandle,
    state: String,
): RearCastSessionHandle? {
    if (current != queried) return current
    return if (state == "CLOSED" || state == "FAILED") null else current
}

class MainActivity : Activity() {
    private lateinit var scoreView: TextView
    private lateinit var statusView: TextView
    private lateinit var keepVisibleButton: Button
    private lateinit var apiView: TextView
    private lateinit var castStatusView: TextView
    private lateinit var castStartButton: Button
    private val sample get() = application as SampleApplication
    private var scoreSubscription: java.io.Closeable? = null
    private var statusSubscription: java.io.Closeable? = null
    private var dataSubscription: Closeable? = null
    private var activityStarted = false
    private var batteryObservationRequested = false
    private var dataObservationEpoch = 0L
    private var castHandle: RearCastSessionHandle? = null
    private var castPending = false
    private var castFailurePhase: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        scoreView = TextView(this).apply { textSize = 30f; gravity = Gravity.CENTER }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(24, 24, 24, 24) }
        val castCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 16)
            setBackgroundColor(android.graphics.Color.rgb(238, 244, 252))
        }
        castCard.addView(TextView(this).apply { text = "背屏投放"; textSize = 21f; gravity = Gravity.CENTER })
        castCard.addView(TextView(this).apply {
            text = "投放触发时的当前主屏任务；宿主确认页会显示实际目标。"
            textSize = 15f; gravity = Gravity.CENTER
        })
        castStartButton = Button(this).apply {
            text = "开始 FULL · 90° 投放"
            isAllCaps = false
            minHeight = 56
            setOnClickListener {
                if (castPending) return@setOnClickListener
                if (castHandle != null) {
                    val message = "请先查询当前投放，确认状态为 CLOSED 或 FAILED 后再开始新投放。"
                    apiView.text = message
                    castStatusView.text = message
                    return@setOnClickListener
                }
                castPending = true
                castFailurePhase = null
                castStartButton.isEnabled = false
                renderCastProgress(RearCastProgress(RearCastProgressStage.PREPARING))
                sample.client.openCastSession(this@MainActivity,
                    RearCastRequest(RearCastMode.VIRTUAL, RearRegion.FULL, contentRotation = 1),
                    onProgress = { progress -> runOnUiThread { renderCastProgress(progress) } }
                ) { result -> runOnUiThread {
                    castPending = false
                    castStartButton.isEnabled = true
                    result.fold({ handle ->
                        castHandle = handle
                        renderCastProgress(RearCastProgress(RearCastProgressStage.READY))
                    }, { error ->
                        val code = error.message?.substringBefore(':') ?: "E_CAST_FAILED"
                        renderCastProgress(RearCastProgress(RearCastProgressStage.FAILED, castFailurePhase ?: "请求", code))
                    })
                } }
            }
        }
        castCard.addView(castStartButton, LinearLayout.LayoutParams(-1, -2))
        castStatusView = TextView(this).apply {
            text = "等待连接 ReMRSS…"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 8)
            minHeight = 64
            contentDescription = text
        }
        castCard.addView(castStatusView, LinearLayout.LayoutParams(-1, -2))
        layout.addView(castCard, LinearLayout.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply { text = "Scoreboard · Main screen"; textSize = 20f; gravity = Gravity.CENTER })
        layout.addView(scoreView, LinearLayout.LayoutParams(-1, 120))
        layout.addView(Button(this).apply { text = "+1 on main"; setOnClickListener { sample.addPoint() } })
        keepVisibleButton = Button(this).apply {
            setOnClickListener {
                sample.keepVisibleForNextScene = !sample.keepVisibleForNextScene
                refreshKeepVisibleLabel()
            }
        }
        layout.addView(keepVisibleButton)
        layout.addView(Button(this).apply {
            text = "Open rear scene"
            setOnClickListener { sample.open(this@MainActivity, { renderStatus(it) }, sample.keepVisibleForNextScene) }
        })
        layout.addView(Button(this).apply {
            text = "Open FULL rear scene · 90°"
            setOnClickListener { sample.open(this@MainActivity, { renderStatus(it) }, sample.keepVisibleForNextScene, contentRotation = 1) }
        })
        layout.addView(Button(this).apply {
            text = "Check rear status"
            setOnClickListener { queryRearState() }
        })
        layout.addView(Button(this).apply {
            text = "Query cast status"
            setOnClickListener {
                val handle = castHandle ?: run { apiView.text = "No ready cast session"; renderCastProgress(RearCastProgress(RearCastProgressStage.FAILED, "QUERY", "E_NO_SESSION")); return@setOnClickListener }
                sample.client.queryCastSession(handle) { result -> runOnUiThread {
                    if (castHandle != handle) return@runOnUiThread
                    result.fold({ snapshot ->
                        castHandle = castHandleAfterQuery(castHandle, handle, snapshot.state)
                        val message = when (snapshot.state) {
                            "CLOSING" -> "投放仍在异步关闭中（CLOSING）；继续查询以确认 CLOSED 或 FAILED。"
                            "CLOSED", "FAILED" -> "投放已结束：${snapshot.state} · ${snapshot.region}"
                            else -> "投放 ${snapshot.state} · ${snapshot.region} · ${snapshot.appliedViewport?.contentRotation ?: snapshot.contentRotation * 90}°"
                        }
                        apiView.text = message
                        castStatusView.text = message
                    }, { error ->
                        apiView.text = "Cast query failed: ${error.message}"
                        castStatusView.text = "查询失败：${error.message}；句柄保留，可重试查询。"
                    })
                } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Request async close"
            setOnClickListener {
                val handle = castHandle ?: run { apiView.text = "No ready cast session"; castStatusView.text = "当前没有就绪的投放会话"; return@setOnClickListener }
                castStatusView.text = "正在关闭自己的投放会话…"
                sample.client.closeCastSession(handle) { result -> runOnUiThread {
                    if (castHandle != handle) return@runOnUiThread
                    result.fold({
                        val message = if (it.success)
                            "关闭请求已提交；关闭为异步操作。请点“Query cast status”确认 CLOSED 或 FAILED。"
                        else "关闭请求未接受：${it.error}；句柄保留，可查询或重试。"
                        apiView.text = message; castStatusView.text = message
                    }, { error ->
                        apiView.text = "Cast close failed: ${error.message}"
                        castStatusView.text = "关闭失败：${error.message}；句柄保留，可查询或重试。"
                    })
                } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Choose rear notification access"
            setOnClickListener {
                sample.client.requestNotificationAuthorization(this@MainActivity) { result -> runOnUiThread {
                    renderStatus(if (result.isSuccess) "Rear notification access screen opened" else "Notification consent failed: ${result.exceptionOrNull()?.message}")
                } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Publish or update rear notice"
            setOnClickListener {
                sample.client.publishNotification(RearNotificationRequest(
                    id = "sample.rear-notice", title = "SDK sample", text = "Tap to update this notice",
                    durationMs = 8_000L
                )) { result -> runOnUiThread {
                    result.fold({ outcome ->
                        val message = when (outcome.status) {
                            RearNotificationSubmitStatus.ACCEPTED -> "Notice accepted; waiting for real display result"
                            RearNotificationSubmitStatus.SUPPRESSED -> "Notice suppressed: ${outcome.reason}"
                            RearNotificationSubmitStatus.REJECTED -> "Notice rejected: ${outcome.reason}"
                        }
                        apiView.text = message
                    }, { renderStatus("Notice failed: ${it.message}") })
                } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Cancel my rear notice"
            setOnClickListener {
                sample.client.cancelNotification("sample.rear-notice") { result -> runOnUiThread {
                    result.fold({ apiView.text = "Cancel ${it.status}: ${it.reason ?: it.id}" }, { renderStatus("Cancel failed: ${it.message}") })
                } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Choose battery data access"
            setOnClickListener {
                sample.client.requestDataAuthorization(this@MainActivity, setOf(
                    RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING
                )) { result -> runOnUiThread { renderStatus(if (result.isSuccess) "Battery access screen opened" else "Data consent failed: ${result.exceptionOrNull()?.message}") } }
            }
        })
        layout.addView(Button(this).apply {
            text = "Read battery data once"
            setOnClickListener {
                sample.client.queryData(setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING)) { result ->
                    runOnUiThread { apiView.text = result.fold(::formatBattery, { "Battery query failed: ${it.message}" }) }
                }
            }
        })
        layout.addView(Button(this).apply {
            text = "Observe battery while this screen is open"
            setOnClickListener {
                if (batteryObservationRequested) return@setOnClickListener
                batteryObservationRequested = true
                val requestEpoch = ++dataObservationEpoch
                sample.client.observeData(setOf(RearDataField.BATTERY_LEVEL_PERCENT, RearDataField.BATTERY_CHARGING),
                    intervalMs = 5_000L, onSnapshot = { snapshot ->
                        runOnUiThread {
                            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                                apiView.text = formatBattery(snapshot)
                            }
                        }
                    }) { result -> runOnUiThread {
                        result.fold(onSuccess = { subscription ->
                            if (activityStarted && batteryObservationRequested && requestEpoch == dataObservationEpoch) {
                                dataSubscription = subscription
                            } else {
                                subscription.close()
                            }
                        }, onFailure = {
                            if (requestEpoch == dataObservationEpoch) {
                                batteryObservationRequested = false
                                renderStatus("Data observation failed: ${it.message}")
                            }
                        })
                    } }
            }
        })
        apiView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(12, 12, 12, 12) }
        layout.addView(apiView, LinearLayout.LayoutParams(-1, -2))
        layout.addView(TextView(this).apply {
            text = "遮挡时使用纯黑画面并暂停刷新；这是遮挡保护，不是真正熄屏，仍可能耗电。"
            textSize = 13f
            gravity = Gravity.CENTER
        })
        statusView = TextView(this).apply { textSize = 14f; gravity = Gravity.CENTER; setPadding(12, 16, 12, 0) }
        layout.addView(statusView, LinearLayout.LayoutParams(-1, -2))
        val scroll = ScrollView(this).apply { clipToPadding = false; addView(layout) }
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
        setContentView(scroll)
        render()
        refreshKeepVisibleLabel()
        renderStatus(sample.lastStatus)
    }

    override fun onResume() {
        super.onResume()
        render()
        if (::statusView.isInitialized) renderStatus(sample.lastStatus)
        sample.connect { result ->
            runOnUiThread { renderStatus(if (result.isSuccess) sample.lastStatus else "Connection failed: ${result.exceptionOrNull()?.message}") }
        }
    }
    override fun onStart() {
        super.onStart()
        activityStarted = true
        scoreSubscription = sample.observeScore { value -> runOnUiThread { if (::scoreView.isInitialized) scoreView.text = value.toString() } }
        statusSubscription = sample.observeStatus { value -> runOnUiThread { renderStatus(value) } }
    }
    override fun onStop() {
        activityStarted = false
        batteryObservationRequested = false
        dataObservationEpoch++
        scoreSubscription?.close()
        scoreSubscription = null
        statusSubscription?.close()
        statusSubscription = null
        dataSubscription?.close()
        dataSubscription = null
        super.onStop()
    }
    private fun render() { if (::scoreView.isInitialized) scoreView.text = sample.score().toString() }
    private fun renderCastProgress(progress: RearCastProgress) {
        if (!::castStatusView.isInitialized) return
        if (progress.stage == RearCastProgressStage.FAILED) castFailurePhase = progress.phase
        val text = when (progress.stage) {
            RearCastProgressStage.PREPARING -> "正在核对目标与背屏状态…"
            RearCastProgressStage.WAITING_CONFIRMATION -> "已准备。请在宿主确认页核对目标，再点“确认并投放”。"
            RearCastProgressStage.CONFIRMED -> "已确认，正在提交到背屏…"
            RearCastProgressStage.COMMIT_ACCEPTED -> "已提交，等待背屏实际启动…"
            RearCastProgressStage.READY -> "背屏已就绪 · FULL · 90°"
            RearCastProgressStage.FAILED -> "${castFailureReason(progress.errorCode)}（${progress.phase ?: "请求"}）"
        }
        castStatusView.text = text
        castStatusView.contentDescription = text
        if (progress.stage == RearCastProgressStage.FAILED || progress.stage == RearCastProgressStage.READY) {
            castPending = false
            if (::castStartButton.isInitialized) castStartButton.isEnabled = true
        }
    }

    private fun castFailureReason(code: String?): String = when (code) {
        "E_USER_CANCELLED" -> "已取消确认"
        "E_TICKET_STALE" -> "确认已过期，请重新开始"
        "E_FOREGROUND_REQUIRED" -> "请回到前台再试"
        "E_PREPARE_FAILED" -> "准备请求失败"
        "E_BACKEND_UNAVAILABLE" -> "背屏后端当前不可用"
        "E_CAPABILITY_DENIED" -> "缺少所需投放能力"
        null -> "投放未成功"
        else -> "投放未成功：$code"
    }
    private fun renderStatus(value: String) { if (::statusView.isInitialized) statusView.text = value }
    private fun configureSystemBars() {
        window.statusBarColor = android.graphics.Color.rgb(250, 250, 250)
        window.navigationBarColor = android.graphics.Color.rgb(250, 250, 250)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }
    private fun refreshKeepVisibleLabel() {
        if (::keepVisibleButton.isInitialized) {
            keepVisibleButton.text = if (sample.keepVisibleForNextScene) "Keep rear screen on · On" else "Keep rear screen on · Off (default)"
        }
    }

    private fun queryRearState() {
        sample.client.queryCapabilities { capabilities ->
            sample.client.queryRearInfo { info -> runOnUiThread {
                val caps = capabilities.getOrNull()
                val rear = info.getOrNull()
                apiView.text = buildString {
                    append("Contract: ${caps?.contract ?: rear?.contract ?: "unavailable"}\n")
                    append("Device available: ${rear?.deviceAvailable ?: caps?.deviceAvailable ?: "unknown"}; backend connected: ${rear?.backendConnected ?: caps?.backendConnected ?: "unknown"}\n")
                    append(rear?.panel?.let { "Rear display ${it.displayId}: ${it.logicalWidthPx}×${it.logicalHeightPx}px, ${it.densityDpi}dpi, ${it.state}" }
                        ?: "Rear panel details unavailable")
                }
                capabilities.exceptionOrNull()?.let { renderStatus("Capability query failed: ${it.message}") }
                info.exceptionOrNull()?.let { renderStatus("Panel query failed: ${it.message}") }
            } }
        }
    }

    private fun formatBattery(snapshot: RearDataSnapshot): String = buildString {
        append("Battery · ")
        append(snapshot.fields[RearDataField.BATTERY_LEVEL_PERCENT].displayValue("level"))
        append(" · ")
        append(snapshot.fields[RearDataField.BATTERY_CHARGING].displayValue("charging"))
    }

    private fun RearDataValue?.displayValue(label: String): String = when (this) {
        is RearDataValue.Number -> "$label ${value.toInt()} ${unit}"
        is RearDataValue.Flag -> "$label ${if (value) "yes" else "no"}"
        is RearDataValue.Unavailable -> "$label unavailable ($reason)"
        null -> "$label unavailable"
    }
}
