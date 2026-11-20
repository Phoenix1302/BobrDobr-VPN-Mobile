package com.bobrdobr.vpn

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.bobrdobr.vpn.ui.BobrDobrMainApp
import com.bobrdobr.vpn.ui.theme.BobrDobrTheme
import org.bobrdobr.vpn.core.service.VpnTunnelService

/**
 * MainActivity: Main entrance activity hosting BobrDobr VPN UI.
 *
 * Provides LocalLifecycleOwner explicitly to guarantee compatibility across
 * Compose 1.6+ and Lifecycle 2.8+ on all Android versions and emulators.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CompositionLocalProvider(
                androidx.lifecycle.compose.LocalLifecycleOwner provides this,
                androidx.compose.ui.platform.LocalLifecycleOwner provides this
            ) {
                BobrDobrTheme {
                    BobrDobrMainApp()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        VpnTunnelService.setUiVisibility(this, true)
    }

    override fun onStop() {
        super.onStop()
        VpnTunnelService.setUiVisibility(this, false)
    }
}
