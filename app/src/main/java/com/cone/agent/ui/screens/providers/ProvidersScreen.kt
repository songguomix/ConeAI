package com.cone.agent.ui.screens.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cone.agent.R
import com.cone.agent.data.remote.FREE_WEEKLY_TOKEN_LIMIT
import com.cone.agent.data.remote.FreeMode
import com.cone.agent.data.remote.FreeStatus
import com.cone.agent.domain.model.Provider
import com.cone.agent.ui.theme.ConeShapes
import java.text.NumberFormat
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersScreen(
    onBack: () -> Unit,
    onEditProvider: (Long) -> Unit,
    viewModel: ProvidersViewModel = hiltViewModel(),
) {
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val freeStatus by viewModel.freeStatus.collectAsStateWithLifecycle()
    val freeLoading by viewModel.freeLoading.collectAsStateWithLifecycle()
    val freeWeeklyUsed by viewModel.freeWeeklyUsed.collectAsStateWithLifecycle(initialValue = 0L)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { viewModel.refreshFree() }
    LaunchedEffect(Unit) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawer_providers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEditProvider(0L) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.providers_add))
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // 底部多留 96dp：否则最后一张卡片会被右下角的悬浮按钮压住。
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "free") {
                FreeProviderCard(
                    status = freeStatus,
                    loading = freeLoading,
                    weeklyUsed = freeWeeklyUsed,
                    onRefresh = viewModel::refreshFree,
                )
            }
            items(providers, key = { it.id }) { provider ->
                ProviderCard(
                    provider = provider,
                    onOpen = { onEditProvider(provider.id) },
                    onDelete = { viewModel.deleteProvider(provider) },
                )
            }
            if (providers.isEmpty()) {
                item {
                    EmptyProviders(
                        modifier = Modifier.fillMaxWidth(),
                        onAdd = { onEditProvider(0L) },
                    )
                }
            }
        }
    }
}

/**
 * 空状态：说明 + 直接可点的「新增 Provider」。
 *
 * 之前只有两行文字，唯一的入口是右下角那个没有文字的悬浮按钮——新用户要猜。
 */
@Composable
private fun EmptyProviders(onAdd: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Build,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            text = stringResource(R.string.providers_empty_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(R.string.providers_empty_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp).padding(top = 8.dp),
        )
        FilledTonalButton(
            onClick = onAdd,
            modifier = Modifier.padding(top = 24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.providers_add))
        }
    }
}

@Composable
private fun FreeProviderCard(
    status: FreeStatus?,
    loading: Boolean,
    weeklyUsed: Long,
    onRefresh: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val date = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val available = status?.available == true && status.models.isNotEmpty()
    val statusLabel = when {
        status == null && loading -> R.string.free_provider_loading
        status == null -> R.string.free_provider_status_unavailable
        available -> R.string.free_provider_available
        status.available -> R.string.free_provider_no_models
        else -> R.string.free_provider_closed
    }
    Card(shape = ConeShapes.Card, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(stringResource(R.string.free_provider_name), style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProviderStatePill(text = stringResource(statusLabel), active = available)
                    if (status?.mode == FreeMode.SCHEDULED) {
                        val start = status.startsAt?.let(date::format)
                        val end = status.endsAt?.let(date::format)
                        val schedule = when {
                            start != null && end != null ->
                                stringResource(R.string.free_provider_schedule, start, end)
                            start != null -> stringResource(R.string.free_provider_opens, start)
                            end != null -> stringResource(R.string.free_provider_closes, end)
                            else -> null
                        }
                        schedule?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                    if (available) {
                        Text(
                            stringResource(
                                R.string.free_provider_weekly_usage,
                                number.format(weeklyUsed),
                                number.format(FREE_WEEKLY_TOKEN_LIMIT),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { (weeklyUsed.toFloat() / FREE_WEEKLY_TOKEN_LIMIT).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        status?.resetsAt?.let {
                            Text(
                                stringResource(R.string.free_provider_cycle_reset, date.format(it)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            },
            trailingContent = {
                IconButton(onClick = onRefresh, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.free_provider_refresh))
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/**
 * 一张 Provider 卡片：整张卡片可点进编辑（Material 3 的可点击 Card，带水波纹与按钮语义），
 * 右侧的删除仍然只响应自己那一小块。
 */
@Composable
private fun ProviderCard(
    provider: Provider,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onOpen,
        shape = ConeShapes.Card,
        modifier = modifier.fillMaxWidth(),
    ) {
        ListItem(
            headlineContent = {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
            },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = provider.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ProviderStatePill(
                        text = stringResource(if (provider.hasApiKey) R.string.providers_key_set else R.string.providers_key_unset),
                        active = provider.hasApiKey,
                    )
                }
            },
            trailingContent = {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.cd_delete))
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

@Composable
private fun ProviderStatePill(text: String, active: Boolean) {
    val container = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = Modifier
            .background(container, CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}
