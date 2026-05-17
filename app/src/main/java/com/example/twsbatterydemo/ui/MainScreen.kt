package com.example.twsbatterydemo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.twsbatterydemo.model.ScanUiState
import com.example.twsbatterydemo.util.TimeUtils

private object BatteryUiColors {
    val BackgroundTop = Color(0xFF0A173D)
    val BackgroundBottom = Color(0xFF091331)
    val CapsuleConnectedBg = Color(0x2D1F4C82)
    val CapsuleIdleBg = Color(0x231E365E)
    val CapsuleReadingBg = Color(0x2D1A4372)
    val ConnectedAccent = Color(0xFF18E58F)
    val ReadingAccent = Color(0xFF5FB3FF)
    val IdleAccent = Color(0xFF6D86AE)
    val Title = Color(0xFFF7FAFF)
    val RingTrack = Color(0xFF22375F)
    val RingProgress = Color(0xFF12F2AE)
    val Number = Color(0xFFF4F7FF)
    val Percent = Color(0xFF819AC4)
    val MetaLabel = Color(0xFF59729F)
    val MetaValue = Color(0xFFDAE5FA)
    val MetaValueMuted = Color(0xFFAAB8D3)
    val PrimaryButton = Color(0xFF4457FF)
    val PrimaryButtonPressed = Color(0xFF3547EA)
    val SecondaryBorder = Color(0xFF243D69)
    val SecondaryText = Color(0xFFCBD8F0)
    val Error = Color(0xFFFF8090)
}

private object BatteryUiMetrics {
    val HorizontalPadding = 28.dp
    val TitleTopSpacing = 18.dp
    val RingSize = 216.dp
    val RingStroke = 6.5.dp
    val MetaTopSpacing = 28.dp
    val MetaValueTopSpacing = 6.dp
    val PrimaryButtonHeight = 56.dp
    val SecondaryButtonHeight = 50.dp
}

@Composable
fun MainScreen(
    state: ScanUiState,
    onRefreshBattery: () -> Unit,
    onExportLogs: () -> Unit
) {
    val batteryLevel = state.batteryReadState.totalBatteryPercent
    val status = when {
        state.batteryReadState.isRefreshing -> StatusUiState.Reading
        state.batteryReadState.isConnected -> StatusUiState.Connected
        else -> StatusUiState.Disconnected
    }

    var playEntrance by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        playEntrance = true
    }

    val targetProgress = if (playEntrance && batteryLevel != null) {
        (batteryLevel.coerceIn(0, 100)) / 100f
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "batteryRingProgress"
    )
    val animatedLevel by animateIntAsState(
        targetValue = if (playEntrance && batteryLevel != null) batteryLevel else 0,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "batteryLevel"
    )
    val ringScale by animateFloatAsState(
        targetValue = if (playEntrance) 1f else 0.96f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "ringScale"
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (playEntrance) 1f else 0f,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "ringAlpha"
    )

    val capsuleAlpha = remember { Animatable(1f) }
    LaunchedEffect(status) {
        capsuleAlpha.snapTo(0.72f)
        capsuleAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
        )
    }

    val syncAlpha = remember { Animatable(1f) }
    LaunchedEffect(state.batteryReadState.lastUpdatedAt) {
        syncAlpha.snapTo(0.68f)
        syncAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
        )
    }

    val syncText = buildSyncText(state.batteryReadState.lastUpdatedAt)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(BatteryUiColors.BackgroundTop, BatteryUiColors.BackgroundBottom)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = BatteryUiMetrics.HorizontalPadding, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            StatusCapsule(
                status = status,
                modifier = Modifier
                    .align(Alignment.Start)
                    .alpha(capsuleAlpha.value)
            )

            Text(
                text = "我的耳机",
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(top = BatteryUiMetrics.TitleTopSpacing),
                color = BatteryUiColors.Title,
                fontSize = 40.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.8).sp
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BatteryHero(
                        batteryLevel = batteryLevel,
                        animatedLevel = animatedLevel,
                        animatedProgress = animatedProgress,
                        modifier = Modifier
                            .scale(ringScale)
                            .alpha(ringAlpha)
                    )

                    Column(
                        modifier = Modifier
                            .padding(top = BatteryUiMetrics.MetaTopSpacing)
                            .alpha(syncAlpha.value),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "最近同步",
                            color = BatteryUiColors.MetaLabel,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.6.sp
                        )
                        Text(
                            text = syncText,
                            modifier = Modifier.padding(top = BatteryUiMetrics.MetaValueTopSpacing),
                            color = if (syncText == "N/A") BatteryUiColors.MetaValueMuted else BatteryUiColors.MetaValue,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = 0.sp
                        )
                        SplitBatteryRow(
                            leftBattery = state.batteryReadState.leftBatteryPercent,
                            rightBattery = state.batteryReadState.rightBatteryPercent,
                            caseBattery = state.batteryReadState.caseBatteryPercent,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            state.errorMessage?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier.padding(bottom = 10.dp),
                    color = BatteryUiColors.Error,
                    fontSize = 12.sp
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RefreshBatteryButton(
                    isLoading = state.batteryReadState.isRefreshButtonBusy,
                    onClick = onRefreshBattery
                )
                SecondaryActionButton(
                    text = "导出日志",
                    onClick = onExportLogs
                )
            }
        }
    }
}

@Composable
private fun SplitBatteryRow(
    leftBattery: Int?,
    rightBattery: Int?,
    caseBattery: Int?,
    modifier: Modifier = Modifier
) {
    val left = leftBattery?.toString() ?: "--"
    val right = rightBattery?.toString() ?: "--"
    val case = caseBattery?.toString() ?: "--"
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "左耳 $left", color = BatteryUiColors.MetaValueMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = "右耳 $right", color = BatteryUiColors.MetaValueMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text(text = "充电仓 $case", color = BatteryUiColors.MetaValueMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class StatusUiState(
    val label: String,
    val background: Color,
    val accent: Color
) {
    Connected("已连接", BatteryUiColors.CapsuleConnectedBg, BatteryUiColors.ConnectedAccent),
    Reading("读取中", BatteryUiColors.CapsuleReadingBg, BatteryUiColors.ReadingAccent),
    Disconnected("未连接", BatteryUiColors.CapsuleIdleBg, BatteryUiColors.IdleAccent)
}

@Composable
private fun StatusCapsule(
    status: StatusUiState,
    modifier: Modifier = Modifier
) {
    val capsuleBackground by animateColorAsState(
        targetValue = status.background,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "capsuleBg"
    )
    val accentColor by animateColorAsState(
        targetValue = status.accent,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "capsuleAccent"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(capsuleBackground)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        BluetoothGlyph(
            modifier = Modifier.size(11.dp),
            tint = accentColor.copy(alpha = 0.9f)
        )
        Text(
            text = status.label,
            color = accentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun BatteryHero(
    batteryLevel: Int?,
    animatedLevel: Int,
    animatedProgress: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.size(BatteryUiMetrics.RingSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = BatteryUiMetrics.RingStroke.toPx()
            val diameter = size.minDimension
            val ringSize = Size(diameter - strokeWidth, diameter - strokeWidth)
            val topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f)

            drawArc(
                color = BatteryUiColors.RingTrack,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = strokeWidth)
            )
            drawArc(
                color = BatteryUiColors.RingProgress,
                startAngle = -82f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = buildAnnotatedString {
                    if (batteryLevel == null) {
                        withStyle(
                            SpanStyle(
                                color = BatteryUiColors.Number,
                                fontSize = 58.sp,
                                fontWeight = FontWeight.Light
                            )
                        ) { append("N/A") }
                    } else {
                        withStyle(
                            SpanStyle(
                                color = BatteryUiColors.Number,
                                fontSize = 66.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = (-2.2).sp
                            )
                        ) { append(animatedLevel.toString()) }
                        withStyle(
                            SpanStyle(
                                color = BatteryUiColors.Percent,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Light,
                                letterSpacing = (-0.8).sp
                            )
                        ) { append("%") }
                    }
                },
                textAlign = TextAlign.Center
            )

            BatteryGlyph(
                modifier = Modifier
                    .padding(top = 9.dp)
                    .size(width = 28.dp, height = 14.dp),
                tint = BatteryUiColors.RingProgress.copy(alpha = 0.9f)
            )
        }
    }
}

@Composable
private fun RefreshBatteryButton(
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.982f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "refreshButtonScale"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "refreshRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(animation = tween(900, easing = LinearEasing)),
        label = "refreshIconRotation"
    )

    Button(
        onClick = onClick,
        enabled = !isLoading,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(BatteryUiMetrics.PrimaryButtonHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = BatteryUiColors.PrimaryButton,
            contentColor = Color.White,
            disabledContainerColor = BatteryUiColors.PrimaryButtonPressed.copy(alpha = 0.82f),
            disabledContentColor = Color.White.copy(alpha = 0.92f)
        )
    ) {
        RefreshGlyph(
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = if (isLoading) rotation else 0f },
            tint = Color.White.copy(alpha = 0.96f)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(
            text = if (isLoading) "刷新中…" else "刷新电量",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SecondaryActionButton(
    text: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.986f else 1f,
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 520f),
        label = "secondaryButtonScale"
    )

    OutlinedButton(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .height(BatteryUiMetrics.SecondaryButtonHeight)
            .scale(scale),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = BatteryUiColors.SecondaryText),
        border = BorderStroke(1.dp, BatteryUiColors.SecondaryBorder)
    ) {
        ShareGlyph(
            modifier = Modifier.size(18.dp),
            tint = BatteryUiColors.SecondaryText.copy(alpha = 0.9f)
        )
        Spacer(modifier = Modifier.size(10.dp))
        Text(text = text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

private fun buildSyncText(lastUpdatedAt: Long): String {
    if (lastUpdatedAt <= 0L) return "N/A"
    return TimeUtils.format(lastUpdatedAt)
}

@Composable
private fun BluetoothGlyph(
    modifier: Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.12f
        val centerX = size.width * 0.44f
        val top = Offset(centerX, size.height * 0.1f)
        val mid = Offset(centerX, size.height * 0.5f)
        val bottom = Offset(centerX, size.height * 0.9f)
        val rightTop = Offset(size.width * 0.8f, size.height * 0.28f)
        val rightBottom = Offset(size.width * 0.8f, size.height * 0.72f)

        drawLine(color = tint, start = top, end = bottom, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = tint, start = top, end = rightTop, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = tint, start = mid, end = rightTop, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = tint, start = mid, end = rightBottom, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = tint, start = bottom, end = rightBottom, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

@Composable
private fun BatteryGlyph(
    modifier: Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.1f
        val bodyWidth = size.width * 0.82f
        val bodyHeight = size.height * 0.72f
        val top = (size.height - bodyHeight) / 2f

        drawRoundRect(
            color = tint,
            topLeft = Offset(0f, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(size.height * 0.18f, size.height * 0.18f),
            style = Stroke(width = strokeWidth)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(size.width * 0.86f, size.height * 0.35f),
            size = Size(size.width * 0.14f, size.height * 0.3f),
            cornerRadius = CornerRadius(size.height * 0.08f, size.height * 0.08f)
        )

        val fillTop = size.height * 0.34f
        val fillHeight = size.height * 0.32f
        val segmentGap = size.width * 0.03f
        val segmentWidth = size.width * 0.17f
        repeat(3) { index ->
            drawRoundRect(
                color = tint.copy(alpha = 0.88f - index * 0.12f),
                topLeft = Offset(size.width * 0.1f + index * (segmentWidth + segmentGap), fillTop),
                size = Size(segmentWidth, fillHeight),
                cornerRadius = CornerRadius(size.height * 0.07f, size.height * 0.07f)
            )
        }
    }
}

@Composable
private fun RefreshGlyph(
    modifier: Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.1f
        val arcRectTopLeft = Offset(size.width * 0.11f, size.height * 0.11f)
        val arcRectSize = Size(size.width * 0.78f, size.height * 0.78f)
        drawArc(
            color = tint,
            startAngle = 38f,
            sweepAngle = 286f,
            useCenter = false,
            topLeft = arcRectTopLeft,
            size = arcRectSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        val arrow = Path().apply {
            moveTo(size.width * 0.74f, size.height * 0.14f)
            lineTo(size.width * 0.92f, size.height * 0.2f)
            lineTo(size.width * 0.8f, size.height * 0.34f)
            close()
        }
        drawPath(path = arrow, color = tint)
    }
}

@Composable
private fun ShareGlyph(
    modifier: Modifier,
    tint: Color
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension * 0.11f
        val left = Offset(size.width * 0.24f, size.height * 0.5f)
        val top = Offset(size.width * 0.71f, size.height * 0.28f)
        val bottom = Offset(size.width * 0.71f, size.height * 0.72f)
        val strokeWidth = size.minDimension * 0.1f

        drawLine(color = tint, start = left, end = top, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color = tint, start = left, end = bottom, strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawCircle(color = tint, radius = radius, center = left)
        drawCircle(color = tint, radius = radius, center = top)
        drawCircle(color = tint, radius = radius, center = bottom)
    }
}
