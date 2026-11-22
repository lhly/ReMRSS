package top.zainzh.rearscreen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Keep the selected main-display task visible outside the card. This is not a new window. */
@Composable internal fun CastApprovalPane(
    application: String,
    mode: String,
    region: String,
    occupancy: String,
    rotationDegrees: Int? = null,
    secondaryLabel: String = "取消",
    submitting: Boolean = false,
    submittingLabel: String = "正在提交…",
    secondaryEnabled: Boolean = !submitting,
    onSecondary: () -> Unit,
    onConfirm: () -> Unit,
    onCancelFlow: (() -> Unit)? = null,
) {
    val scroll = rememberScrollState()
    var detailsReached by remember(application, mode, region, occupancy, rotationDegrees) { mutableStateOf(false) }
    LaunchedEffect(application, mode, region, occupancy, rotationDegrees, scroll) {
        // Previously confirm followed the disclosures in the scrolling body. Keeping it in view
        // shouldn't make it possible to confirm without reaching the end of longer disclosures.
        snapshotFlow { scroll.maxValue != Int.MAX_VALUE && scroll.value >= scroll.maxValue }.collect {
            if (it) detailsReached = true
        }
    }
    val subtitle = if (detailsReached) "确认前不会切换任务，提交时会重新核验。"
        else "向下滑动查看完整详情，再确认投放。"
    CastDecisionSurface("确认投放到背屏", scroll = scroll, subtitle = subtitle, footer = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AppActionPair(secondaryLabel, onSecondary,
                if (submitting) submittingLabel else "确认并投放", onConfirm,
                secondaryModifier = Modifier.testTag("cast_approval_secondary"),
                primaryModifier = Modifier.testTag("cast_approval_confirm"),
                secondaryEnabled = secondaryEnabled, primaryEnabled = detailsReached && !submitting)
            if (onCancelFlow != null) {
                TextButton("退出投放", onCancelFlow, Modifier.align(Alignment.CenterHorizontally)
                    .testTag("cast_approval_exit"), minHeight = 48.dp,
                    colors = ButtonDefaults.textButtonColors().copy(color = Color.Transparent,
                        textColor = AppPalette.secondary()))
            }
        }
    }) {
        Text("目标应用：$application", color = AppPalette.text())
        Text(occupancy, color = AppPalette.text())
        Text("投放方式：$mode", color = AppPalette.secondary())
        Text("背屏区域：$region", color = AppPalette.secondary())
        rotationDegrees?.let { Text("内容方向：${it}°", color = AppPalette.secondary()) }
    }
}

@Composable internal fun CastApprovalFailure(title: String, message: String, onReturn: () -> Unit) {
    CastDecisionSurface(title, footer = {
        TextButton("返回", onReturn, Modifier.fillMaxWidth().testTag("cast_approval_return"), minHeight = 48.dp)
    }) {
        Text(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = AppPalette.secondary())
    }
}

@Composable private fun CastDecisionSurface(
    title: String,
    scroll: ScrollState = rememberScrollState(),
    subtitle: String? = null,
    footer: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 680.dp).fillMaxWidth()) {
            val surface = CardDefaults.defaultColors().color
            val fadeDistance = with(LocalDensity.current) { 12.dp.toPx() }
            AppCard {
                AppDecisionLayout(Modifier.fillMaxWidth().padding(16.dp),
                    bodyModifier = Modifier.testTag("cast_approval_details").pageBodyFade(surface, depth = 18.dp) {
                        (scroll.value / fadeDistance).coerceIn(0f, 1f)
                    }, scroll = scroll, spacing = 10.dp,
                    heading = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AppDecisionHeading(title)
                            subtitle?.let { Text(it, color = AppPalette.secondary(), fontSize = 13.sp) }
                        }
                    }, footer = footer, body = body)
            }
        }
    }
}
