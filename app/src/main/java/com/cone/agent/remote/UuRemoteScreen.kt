package com.cone.agent.remote

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R

/** UU supplies the remote screen and input transport; ConeAI keeps its native workbench. */
@Composable
internal fun UuRemoteScreen(viewModel: RemoteViewModel) {
    val cx = LocalRemotePalette.current
    val state by viewModel.uuState.collectAsStateWithLifecycle()
    val remote by viewModel.state.collectAsStateWithLifecycle()
    val computerId = viewModel.remoteComputerId
    val bound = computerId != null && state.devices.containsKey(computerId)
    val event = state.events[computerId]
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var websiteFailed by remember { mutableStateOf(false) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, next -> if (next == Lifecycle.Event.ON_RESUME) viewModel.refreshUu() }
        owner.lifecycle.addObserver(observer)
        viewModel.refreshUu()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.uu_title), color = cx.text, style = RemoteType.serifBody)
            Text(stringResource(R.string.uu_intro), color = cx.muted, style = RemoteType.body)
            remote.endpointLabel?.let { Text(it, color = cx.muted, style = RemoteType.mono) }
            Card(colors = CardDefaults.cardColors(containerColor = cx.bg2)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else if (state.availability != UuAvailability.READY) {
                        Text(stringResource(if (state.availability == UuAvailability.MISSING) R.string.uu_install else R.string.uu_update),
                            color = cx.text, style = RemoteType.bodyLarge)
                        Button(onClick = {
                            websiteFailed = runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UuRemoteProtocol.DOWNLOAD_URL)))
                            }.isFailure
                        }) { Text(stringResource(R.string.uu_download)) }
                        TextButton(onClick = viewModel::refreshUu) { Text(stringResource(R.string.uu_check_install)) }
                    } else {
                        Text(stringResource(if (bound) R.string.uu_bound else R.string.uu_not_bound), color = cx.text, style = RemoteType.bodyLarge)
                        Text(stringResource(R.string.uu_bind_hint), color = cx.muted, style = RemoteType.bodySmall)
                        Button(enabled = computerId != null && !state.loading,
                            onClick = { computerId?.let(viewModel::bindUu) }) {
                            Text(stringResource(if (bound) R.string.uu_rebind else R.string.uu_bind))
                        }
                        if (bound) {
                            HorizontalDivider(color = cx.border)
                            Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                computerId?.let { viewModel.controlUu(it, UuRemoteProtocol.Mode.FULLSCREEN) }
                            }) { Text(stringResource(R.string.uu_fullscreen)) }
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                                computerId?.let { viewModel.controlUu(it, UuRemoteProtocol.Mode.PIP) }
                            }) { Text(stringResource(R.string.uu_pip)) }
                            Text(stringResource(R.string.uu_modes_hint), color = cx.muted, style = RemoteType.bodySmall)
                            TextButton(onClick = { computerId?.let(viewModel::forgetUu) }) {
                                Text(stringResource(R.string.uu_forget), color = cx.danger)
                            }
                        }
                    }
                }
            }
            if (computerId == null) Text(stringResource(R.string.uu_connect_first), color = cx.muted, style = RemoteType.bodySmall)
            if (event != null) {
                val waiting = event.stage == UuStage.BINDING || event.stage == UuStage.PIP_WAITING
                Text(uuEventText(event), color = if (event.stage == UuStage.ERROR && event.reason != "user_cancel") cx.danger else cx.muted,
                    style = RemoteType.body)
                if (waiting) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { computerId?.let(viewModel::stopWaitingUu) }) {
                        Text(stringResource(R.string.uu_stop_waiting))
                    }
                }
            }
            if (websiteFailed) Text(stringResource(R.string.uu_launch_failed), color = cx.danger, style = RemoteType.bodySmall)
        }
    }
}

@Composable
private fun uuEventText(event: UuEvent): String = stringResource(when (event.stage) {
    UuStage.BOUND -> R.string.uu_bound
    UuStage.BINDING -> R.string.uu_wait_bind
    UuStage.FULLSCREEN_LAUNCHED -> R.string.uu_fullscreen_launched
    UuStage.PIP_WAITING -> R.string.uu_wait_pip
    UuStage.PIP_READY -> R.string.uu_pip_ready
    UuStage.ERROR -> when (event.reason) {
        "no_device" -> R.string.uu_no_device
        "user_cancel" -> R.string.uu_cancelled
        "timeout", "login_timeout" -> R.string.uu_login_timeout
        "not_logined", "logout" -> R.string.uu_login_required
        "superseded" -> R.string.uu_superseded
        "busy" -> R.string.uu_busy
        "device_offline" -> R.string.uu_offline
        "device_not_found" -> R.string.uu_device_not_found
        "pip_unsupported" -> R.string.uu_pip_unsupported
        "network_unavailable" -> R.string.uu_network
        "not_allowed" -> R.string.uu_not_allowed
        "connect_timeout" -> R.string.uu_connect_timeout
        "not_installed" -> R.string.uu_install
        "update_required" -> R.string.uu_update
        "storage_error" -> R.string.uu_storage_error
        "launch_failed" -> R.string.uu_launch_failed
        else -> R.string.uu_error
    }
})
