package be.mygod.vpnhotspot.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.mygod.vpnhotspot.R
import be.mygod.vpnhotspot.SuiteB192HotspotService
import be.mygod.vpnhotspot.util.bindServiceFlow

@Composable
fun SuiteB192TetheringRow() {
    val context = LocalContext.current
    val inspectionMode = LocalInspectionMode.current
    val binder by if (inspectionMode) {
        remember { mutableStateOf<SuiteB192HotspotService.Binder?>(null) }
    } else produceState<SuiteB192HotspotService.Binder?>(null, context) {
        try {
            context.bindServiceFlow(Intent(context, SuiteB192HotspotService::class.java)).collect { service ->
                value = service as? SuiteB192HotspotService.Binder
            }
        } finally {
            value = null
        }
    }
    val active by binder?.active?.collectAsStateWithLifecycle(false)
        ?: remember { mutableStateOf(false) }
    PreferenceSwitchRow(
        icon = R.drawable.ic_wifi_lock,
        title = stringResource(R.string.tethering_suiteb192),
        summary = stringResource(R.string.tethering_suiteb192_summary),
        checked = active,
        enabled = inspectionMode || binder != null,
        onCheckedChange = { enabled ->
            if (inspectionMode) return@PreferenceSwitchRow
            if (enabled) {
                context.startForegroundService(Intent(context, SuiteB192HotspotService::class.java))
            } else binder?.stop() ?: context.stopService(Intent(context, SuiteB192HotspotService::class.java))
        },
    )
}
