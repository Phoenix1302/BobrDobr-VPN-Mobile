package com.bobrdobr.vpn.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bobrdobr.vpn.split.AppInfo
import com.bobrdobr.vpn.split.SplitTunnelManager
import com.bobrdobr.vpn.split.SplitTunnelMode
import com.bobrdobr.vpn.ui.theme.BackgroundCard
import com.bobrdobr.vpn.ui.theme.BackgroundCardActive
import com.bobrdobr.vpn.ui.theme.BackgroundMain
import com.bobrdobr.vpn.ui.theme.BackgroundSurface
import com.bobrdobr.vpn.ui.theme.CardBorder
import com.bobrdobr.vpn.ui.theme.NeonAmber
import com.bobrdobr.vpn.ui.theme.NeonCyan
import com.bobrdobr.vpn.ui.theme.NeonEmerald
import com.bobrdobr.vpn.ui.theme.TextMuted
import com.bobrdobr.vpn.ui.theme.TextPrimary
import com.bobrdobr.vpn.ui.theme.TextSecondary
import com.bobrdobr.vpn.ui.viewmodel.MainViewModel

/**
 * SplitTunnelScreen: Per-App Split Tunneling configuration screen in BobrDobr cyber theme.
 */
@Composable
fun SplitTunnelScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel? = null
) {
    val context = LocalContext.current
    val splitManager = remember { SplitTunnelManager.getInstance(context) }

    val isEnabled by splitManager.isEnabled.collectAsStateWithLifecycle()
    val mode by splitManager.mode.collectAsStateWithLifecycle()
    val selectedApps by splitManager.selectedApps.collectAsStateWithLifecycle()

    val initialEnabled = remember { isEnabled }
    val initialMode = remember { mode }
    val initialApps = remember { selectedApps }

    DisposableEffect(Unit) {
        onDispose {
            if (viewModel?.isTunnelActive() == true) {
                val changed = isEnabled != initialEnabled || mode != initialMode || selectedApps != initialApps
                if (changed) {
                    viewModel.restartTunnelIfActive(context)
                }
            }
        }
    }

    var rawApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilterTab by remember { mutableIntStateOf(0) } // 0 = Все, 1 = Пользовательские, 2 = Системные

    LaunchedEffect(Unit) {
        isLoading = true
        rawApps = splitManager.loadInstalledApps()
        isLoading = false
    }

    val filteredApps = remember(rawApps, searchQuery, selectedFilterTab, selectedApps) {
        rawApps.filter { app ->
            val matchesSearch = searchQuery.isBlank() ||
                    app.name.contains(searchQuery, ignoreCase = true) ||
                    app.packageName.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedFilterTab) {
                1 -> !app.isSystem
                2 -> app.isSystem
                else -> true
            }
            matchesSearch && matchesTab
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundMain)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Top Header with Back Button
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
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "Раздельное туннелирование",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 17.sp
                    )
                )
                Text(
                    text = "Per-App Split Tunneling",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = NeonCyan,
                        fontSize = 11.sp
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Master Switch Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BackgroundCard)
                .border(
                    1.dp,
                    if (isEnabled) NeonEmerald.copy(alpha = 0.5f) else CardBorder,
                    RoundedCornerShape(16.dp)
                )
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Включить разделение трафика",
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Text(
                        text = if (isEnabled) "Правила маршрутизации активны" else "Весь трафик направляется через шлюз",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (isEnabled) NeonEmerald else TextSecondary,
                            fontSize = 12.sp
                        )
                    )
                }

                Switch(
                    checked = isEnabled,
                    onCheckedChange = {
                        splitManager.setEnabled(it)
                        if (viewModel?.isTunnelActive() == true) {
                            viewModel.restartTunnelIfActive(context)
                        }
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = BackgroundMain,
                        checkedTrackColor = NeonEmerald,
                        uncheckedThumbColor = TextMuted,
                        uncheckedTrackColor = BackgroundCardActive
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Mode Selector: Whitelist vs Blacklist
        if (isEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Whitelist Mode
                ModeSelectionChip(
                    title = "Whitelist",
                    subtitle = "Только выбранные",
                    isSelected = mode == SplitTunnelMode.WHITELIST,
                    activeColor = NeonEmerald,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        splitManager.setMode(SplitTunnelMode.WHITELIST)
                        if (viewModel?.isTunnelActive() == true) {
                            viewModel.restartTunnelIfActive(context)
                        }
                    }
                )

                // Blacklist Mode
                ModeSelectionChip(
                    title = "Blacklist",
                    subtitle = "Все кроме выбранных",
                    isSelected = mode == SplitTunnelMode.BLACKLIST,
                    activeColor = NeonAmber,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        splitManager.setMode(SplitTunnelMode.BLACKLIST)
                        if (viewModel?.isTunnelActive() == true) {
                            viewModel.restartTunnelIfActive(context)
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = "Поиск по названию или пакету...",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Очистить",
                            tint = TextMuted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            },
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = BackgroundCard,
                unfocusedContainerColor = BackgroundCard,
                focusedBorderColor = NeonCyan,
                unfocusedBorderColor = CardBorder,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Tabs: Все / Пользовательские / Системные
        TabRow(
            selectedTabIndex = selectedFilterTab,
            containerColor = BackgroundSurface,
            contentColor = TextPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedFilterTab]),
                    color = NeonCyan,
                    height = 2.dp
                )
            },
            divider = {}
        ) {
            val userCount = remember(rawApps) { rawApps.count { !it.isSystem } }
            val sysCount = remember(rawApps) { rawApps.count { it.isSystem } }

            Tab(
                selected = selectedFilterTab == 0,
                onClick = { selectedFilterTab = 0 },
                text = {
                    Text(
                        text = "Все (${rawApps.size})",
                        fontSize = 12.sp,
                        fontWeight = if (selectedFilterTab == 0) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedFilterTab == 0) NeonCyan else TextSecondary
                    )
                }
            )
            Tab(
                selected = selectedFilterTab == 1,
                onClick = { selectedFilterTab = 1 },
                text = {
                    Text(
                        text = "Польз. ($userCount)",
                        fontSize = 12.sp,
                        fontWeight = if (selectedFilterTab == 1) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedFilterTab == 1) NeonCyan else TextSecondary
                    )
                }
            )
            Tab(
                selected = selectedFilterTab == 2,
                onClick = { selectedFilterTab = 2 },
                text = {
                    Text(
                        text = "Системные ($sysCount)",
                        fontSize = 12.sp,
                        fontWeight = if (selectedFilterTab == 2) FontWeight.Bold else FontWeight.Normal,
                        color = if (selectedFilterTab == 2) NeonCyan else TextSecondary
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Quick Actions Row: Выбрать все / Очистить
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Выбрано: ${selectedApps.size} прил.",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = if (selectedApps.isNotEmpty()) NeonEmerald else TextMuted,
                    fontWeight = FontWeight.Bold
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundCardActive)
                        .clickable {
                            val targetPkgs = filteredApps.map { it.packageName }
                            splitManager.selectAll(targetPkgs)
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Выбрать все",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = NeonCyan,
                            fontSize = 11.sp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(BackgroundCardActive)
                        .clickable { splitManager.clearAll() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Очистить",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = NeonAmber,
                            fontSize = 11.sp
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Apps List or Loading Spinner
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = NeonCyan, modifier = Modifier.size(36.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Сбор установленных приложений...",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredApps, key = { it.packageName }) { app ->
                    val isChecked = selectedApps.contains(app.packageName)
                    AppItemRow(
                        app = app,
                        isSelected = isChecked,
                        onToggle = { splitManager.toggleApp(app.packageName) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun ModeSelectionChip(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) activeColor.copy(alpha = 0.12f) else BackgroundCard)
            .border(
                1.dp,
                if (isSelected) activeColor else CardBorder,
                RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(10.dp)
    ) {
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isSelected) activeColor else TextPrimary,
                    fontSize = 13.sp
                )
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary,
                    fontSize = 10.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AppItemRow(
    app: AppInfo,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) BackgroundCardActive else BackgroundCard)
            .border(
                1.dp,
                if (isSelected) NeonEmerald.copy(alpha = 0.4f) else CardBorder.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App Icon
        AppIconImage(
            drawable = app.icon,
            modifier = Modifier.size(36.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and Package
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = app.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                if (app.isSystem) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(CardBorder)
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "SYS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextMuted,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }

            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextMuted,
                    fontSize = 10.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Neon Checkbox
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = NeonEmerald,
                uncheckedColor = TextMuted,
                checkmarkColor = BackgroundMain
            )
        )
    }
}

@Composable
private fun AppIconImage(
    drawable: Drawable?,
    modifier: Modifier = Modifier
) {
    val bitmap = remember(drawable) {
        if (drawable == null) null
        else {
            try {
                if (drawable is BitmapDrawable) {
                    drawable.bitmap.asImageBitmap()
                } else {
                    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceIn(48, 192) else 96
                    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceIn(48, 192) else 96
                    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                    bmp.asImageBitmap()
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(BackgroundCardActive),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Apps,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
