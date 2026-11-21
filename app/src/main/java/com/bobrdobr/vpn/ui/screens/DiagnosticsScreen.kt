package com.bobrdobr.vpn.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.debug.DebugLogger
import com.bobrdobr.vpn.debug.DiagnosticDumpManager
import com.bobrdobr.vpn.debug.LogEntry
import com.bobrdobr.vpn.debug.LogLevel
import com.bobrdobr.vpn.ui.components.UpdateDialog
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.NeonPurple
import com.bobrdobr.vpn.ui.theme.NeonRed
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.theme.neonGlow
import com.bobrdobr.vpn.update.AppUpdateManager
import com.bobrdobr.vpn.update.UpdateState

/**
 * DiagnosticsScreen: Cyberpunk developer and diagnostics screen.
 * Displays component specifications, debug mode toggles, diagnostic dumping,
 * and a real-time syntax-highlighted terminal log viewer.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val isDebugMode by DebugLogger.isDebugModeEnabled.collectAsStateWithLifecycle()
    val logs by DebugLogger.logsFlow.collectAsStateWithLifecycle()

    val updateManager = remember { AppUpdateManager.getInstance(context) }
    val includePrereleases by updateManager.includePrereleases.collectAsStateWithLifecycle()
    val updateState by updateManager.updateState.collectAsStateWithLifecycle()
    var showUpdateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        updateManager.manualCheckEvent.collect { result ->
            when (result) {
                is AppUpdateManager.ManualCheckResult.UpToDate -> {
                    Toast.makeText(context, "У вас установлена последняя версия (${AppUpdateManager.CURRENT_VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
                is AppUpdateManager.ManualCheckResult.Available -> {
                    showUpdateDialog = true
                }
                is AppUpdateManager.ManualCheckResult.Failed -> {
                    Toast.makeText(context, "Сбой проверки обновлений: ${result.error}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var isTerminalExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Top App Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(BackgroundCard)
                    .border(1.dp, CardBorder, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = NeonCyan
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column {
                Text(
                    text = "ОТЛАДКА И ДИАГНОСТИКА",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        color = TextPrimary,
                        letterSpacing = 1.sp
                    )
                )
                Text(
                    text = "Инженерная консоль и телеметрия ядра",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = NeonCyan,
                        fontSize = 11.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 1: Specifications & Component Assembly
        DiagnosticsSectionHeader(title = "СПЕЦИФИКАЦИЯ И СБОРКА КОМПОНЕНТОВ")

        DiagnosticsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                ComponentSpecRow("Клиент", "BobrDobr VPN Mobile v0.8.11-prerelease (Сборка 91)", NeonCyan)
                SpecDivider()
                ComponentSpecRow("Базовая платформа", "Android Target SDK 35 (Android 15 Ready) / Min SDK 26", TextPrimary)
                SpecDivider()
                ComponentSpecRow("UI & Runtime", "Jetpack Compose BOM 2024.12.01 / Kotlin 2.1.0 / Material 3", TextPrimary)
                SpecDivider()
                ComponentSpecRow("NDK & Toolchain", "Android NDK r27c (LLVM Clang 18) / C++20", TextPrimary)
                SpecDivider()
                ComponentSpecRow("16 KB Page-Size", "Выравнивание ELF LOAD 0x4000 (Проверено)", NeonEmerald)
                SpecDivider()
                ComponentSpecRow("Ядро AmneziaWG", "Rust Edition 2021 + amnezia-wireguard-core + BoringSSL/ring", NeonAmber)
                SpecDivider()
                ComponentSpecRow("Ядро OpenConnect", "Go 1.23.4 / AnyConnect CSTP/DTLS + uTLS Chrome Fingerprint", NeonCyan)
                SpecDivider()
                ComponentSpecRow("Архитектуры ABI", Build.SUPPORTED_ABIS.joinToString(", "), TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 2: Debug Mode Toggle
        DiagnosticsSectionHeader(title = "РЕЖИМ ОТЛАДКИ (DEBUG MODE)")

        DiagnosticsCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (isDebugMode) NeonAmber.copy(alpha = 0.15f) else BackgroundCardActive),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = if (isDebugMode) NeonAmber else TextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Режим расширенной отладки",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                        )
                        Text(
                            text = "Включает детальное JNI/сетевое логирование и сбор дампа",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Switch(
                    checked = isDebugMode,
                    onCheckedChange = { DebugLogger.setDebugMode(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundMain,
                        checkedTrackColor = NeonAmber,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = BackgroundCardActive
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 3: Update Channel (PRE-RELEASE vs STABLE)
        DiagnosticsSectionHeader(title = "КАНАЛ ОБНОВЛЕНИЙ (UPDATE CHANNEL)")

        DiagnosticsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background((if (includePrereleases) NeonPurple else NeonEmerald).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudSync,
                                contentDescription = null,
                                tint = if (includePrereleases) NeonPurple else NeonEmerald,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = "Получать предварительные сборки",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 14.sp
                                )
                            )
                            Text(
                                text = "Позволяет скачивать pre-release версии. По умолчанию активно для тестовых сборок и выключено для стабильных.",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = TextSecondary,
                                    fontSize = 11.5.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Switch(
                        checked = includePrereleases,
                        onCheckedChange = { updateManager.setIncludePrereleases(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BackgroundMain,
                            checkedTrackColor = NeonPurple,
                            uncheckedThumbColor = TextMuted,
                            uncheckedTrackColor = BackgroundCardActive
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                SpecDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // Informational Status Plate
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            (if (includePrereleases) NeonPurple else NeonEmerald).copy(alpha = 0.08f)
                        )
                        .border(
                            1.dp,
                            (if (includePrereleases) NeonPurple else NeonEmerald).copy(alpha = 0.35f),
                            RoundedCornerShape(10.dp)
                        )
                        .padding(10.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        (if (includePrereleases) NeonPurple else NeonEmerald).copy(alpha = 0.2f)
                                    )
                                    .border(
                                        1.dp,
                                        if (includePrereleases) NeonPurple else NeonEmerald,
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (includePrereleases) "КАНАЛ: PRE-RELEASE & BETA" else "КАНАЛ: ТОЛЬКО СТАБИЛЬНЫЕ (STABLE)",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (includePrereleases) NeonPurple else NeonEmerald,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = if (includePrereleases) {
                                "Приложение отслеживает как стабильные, так и пререлизные сборки на GitHub."
                            } else {
                                "Приложение игнорирует пререлизы и обновляется только на проверенные стабильные релизы."
                            },
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary,
                                fontSize = 11.5.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Button: Check updates right now
                val isCheckingUpdate = updateState is UpdateState.Checking
                Button(
                    onClick = { updateManager.check(isManual = true) },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (includePrereleases) NeonPurple else NeonCyan,
                        contentColor = BackgroundMain
                    )
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = BackgroundMain,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Проверка обновлений...",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Проверить обновления прямо сейчас",
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 4: Diagnostic Dump (Share Full Dump)
        DiagnosticsSectionHeader(title = "ДИАГНОСТИЧЕСКИЙ ДАМП")

        DiagnosticsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Сбор полного отчета системы",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                )
                Text(
                    text = "Формирует текстовый дамп со спецификациями устройства, состоянием туннелей, маршрутами и логами для отправки в поддержку или разработчику.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 11.5.sp
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        DiagnosticDumpManager.shareDump(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .neonGlow(color = NeonCyan, radius = 6.dp, alpha = 0.25f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NeonCyan,
                        contentColor = BackgroundMain
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сформировать и отправить дамп",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 5: Real-time Terminal Log Viewer
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DiagnosticsSectionHeader(title = "ТЕРМИНАЛ ЛОГОВ РЕАЛЬНОГО ВРЕМЕНИ")

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Expand / Collapse
                IconButton(
                    onClick = { isTerminalExpanded = !isTerminalExpanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isTerminalExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isTerminalExpanded) "Свернуть" else "Раскрыть",
                        tint = NeonCyan
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                // Copy Logs
                IconButton(
                    onClick = {
                        val text = DebugLogger.getAllLogsText()
                        clipboardManager.setText(AnnotatedString(text))
                        Toast.makeText(context, "Логи скопированы в буфер обмена", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Копировать",
                        tint = NeonCyan
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                // Clear Logs
                IconButton(
                    onClick = {
                        DebugLogger.clearLogs()
                        Toast.makeText(context, "Логи очищены", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Очистить",
                        tint = NeonRed
                    )
                }
            }
        }

        // Cyberpunk Terminal Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize()
                .height(if (isTerminalExpanded) 390.dp else 190.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF050811))
                .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            val listState = rememberLazyListState()

            // Auto-scroll to bottom on new log arrival
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    listState.scrollToItem(logs.size - 1)
                }
            }

            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Терминал пуст. Ожидание сетевых событий...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = TextMuted
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(logs, key = { it.id }) { log ->
                        TerminalLogLine(log = log)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Section 6: Experimental Features (Preview)
        DiagnosticsSectionHeader(title = "ЭКСПЕРИМЕНТАЛЬНЫЕ ФУНКЦИИ")

        DiagnosticsCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(NeonPurple.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = null,
                                tint = NeonPurple,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Text(
                            text = "Лаборатория инноваций",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 14.sp
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(NeonPurple.copy(alpha = 0.18f))
                            .border(1.dp, NeonPurple, RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "PREVIEW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NeonPurple,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.5.sp
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Скоро тут появятся новые функции: WireGuard Over TCP, Custom MTU, Multi-Hop обход DPI и аппаратное ускорение криптографии.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(30.dp))
    }

    val availableRelease = (updateState as? UpdateState.UpdateAvailable)?.release
        ?: (updateState as? UpdateState.Downloading)?.release
        ?: (updateState as? UpdateState.ReadyToInstall)?.release

    if (showUpdateDialog && availableRelease != null) {
        UpdateDialog(
            release = availableRelease,
            updateState = updateState,
            onDismiss = { showUpdateDialog = false },
            onDownloadAndInstall = { rel ->
                updateManager.downloadAndInstall(rel)
            }
        )
    }
}

@Composable
private fun DiagnosticsSectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(
            color = TextMuted,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        ),
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun DiagnosticsCard(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BackgroundCard)
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
private fun ComponentSpecRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                color = valueColor,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        )
    }
}

@Composable
private fun SpecDivider() {
    HorizontalDivider(
        color = CardBorder.copy(alpha = 0.35f),
        thickness = 1.dp,
        modifier = Modifier.padding(vertical = 3.dp)
    )
}

@Composable
private fun TerminalLogLine(log: LogEntry) {
    val levelColor = when (log.level) {
        LogLevel.INFO -> NeonEmerald
        LogLevel.DEBUG -> NeonCyan
        LogLevel.WARN -> NeonAmber
        LogLevel.ERROR -> NeonRed
    }

    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = TextMuted, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)) {
                append("${log.timestamp} ")
            }
            withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)) {
                append("${log.level.tag} ")
            }
            withStyle(SpanStyle(color = NeonPurple, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)) {
                append("[${log.tag}] ")
            }
            withStyle(SpanStyle(color = TextPrimary, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)) {
                append(log.message)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
    )
}
