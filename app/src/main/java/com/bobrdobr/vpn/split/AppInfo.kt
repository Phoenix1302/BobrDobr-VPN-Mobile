package com.bobrdobr.vpn.split

import android.graphics.drawable.Drawable

/**
 * Representation of an installed application on the Android device
 * for BobrDobr VPN split tunneling routing rules.
 */
data class AppInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable? = null,
    val isSystem: Boolean = false,
    val isSelected: Boolean = false
)
