package com.msp1974.vacompanion.ui.components

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.msp1974.vacompanion.service.AudioRouteOption
import com.msp1974.vacompanion.ui.DiagnosticInfo
import com.msp1974.vacompanion.ui.theme.CustomColours


@SuppressLint("DefaultLocale")
@Composable
fun DiagnosticBar(
    diagnosticInfo: DiagnosticInfo,
    modifier: Modifier = Modifier,
) {
    val gaugeSize = 120.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .zIndex(2f)
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.8f))
            .pointerInput(Unit) {
                // Prevent propagation of click
            }
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InfoGauge(
                    canvasSize = gaugeSize,
                    indicatorValue = (diagnosticInfo.audioLevel).toInt(),
                    maxIndicatorValue = 100,
                    smallText = "Mic Level",
                    foregroundIndicatorColor = CustomColours.GREEN
                )
                if (diagnosticInfo.wakeWord != "none") {
                    InfoGauge(
                        canvasSize = gaugeSize,
                        indicatorValue = (diagnosticInfo.wakeWordDetectionLevel).toInt(),
                        maxIndicatorValue = 100,
                        smallText = "Wake word",
                        foregroundIndicatorColor = if (diagnosticInfo.wakeWordDetectionLevel >= diagnosticInfo.wakeWordThreshold) CustomColours.GREEN else CustomColours.AMBER
                    )
                }
                if (diagnosticInfo.wakeWord != "none" && diagnosticInfo.engine == "microwakeword") {
                    InfoGauge(
                        canvasSize = gaugeSize,
                        indicatorValue = (diagnosticInfo.stopWordDetectionLevel).toInt(),
                        maxIndicatorValue = 100,
                        smallText = "Stop word",
                        foregroundIndicatorColor = if (diagnosticInfo.stopWordDetectionLevel >= diagnosticInfo.stopWordThreshold) CustomColours.GREEN else CustomColours.AMBER
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (diagnosticInfo.engine != "") diagnosticInfo.engine else "DISABLED") },
                    enabled = diagnosticInfo.wakeWord != "none",
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Detecting" ) },
                    enabled = diagnosticInfo.wakeWord != "none",
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (diagnosticInfo.mode == AudioRouteOption.DETECT) CustomColours.GREEN else Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer

                    )
                )
                AssistChip(
                    onClick = {},
                    label = { Text("Streaming") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (diagnosticInfo.mode == AudioRouteOption.STREAM) CustomColours.GREEN else Color.Transparent,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer

                    )
                )
            }
        }
    }

}

@Preview(apiLevel = 35)
@Composable
fun DiagnosticBarPreview() {
    DiagnosticBar(
        modifier = Modifier.background(Color.White),
        diagnosticInfo = DiagnosticInfo(
            audioLevel = 50f,
            engine = "microwakeword",
            wakeWordDetectionLevel = 80f,
            wakeWordThreshold = 50f,
            stopWordDetectionLevel = 70f,
            stopWordThreshold = 50f,
            vadDetection = true
        )
    )

}