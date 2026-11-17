package com.cone.agent.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cone.agent.R
import com.cone.agent.data.local.entity.VisionModelView
import com.cone.agent.data.remote.FREE_PROVIDER_ID
import com.cone.agent.ui.theme.ConeShapes

/**
 * The top-bar model pill: shows the model in use, tap to switch inline (works even mid-answer).
 *
 * Shared by 问答/智能体 and 写代码 — the same control in the same slot on every screen that talks to
 * a model, so switching model is one gesture wherever the user happens to be.
 */
@Composable
fun ModelSwitcher(
    modelLabel: String?,
    models: List<VisionModelView>,
    activeProviderId: Long?,
    activeModelId: String?,
    onSelect: (Long, String) -> Unit,
    onOpenProviders: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Read the localized resources before entering the popup's own context.
    val freeProviderName = stringResource(R.string.free_provider_name)
    val manageProviders = stringResource(R.string.drawer_providers)
    val noModels = stringResource(R.string.settings_no_models)
    val searchModels = stringResource(R.string.model_search_hint)
    val clearSearch = stringResource(R.string.model_search_clear)
    val noMatches = stringResource(R.string.model_search_empty)
    val matches = remember(models, searchQuery, freeProviderName) {
        val keyword = searchQuery.trim()
        if (keyword.isEmpty()) models else models.filter { model ->
            model.displayName.contains(keyword, ignoreCase = true) ||
                model.modelId.contains(keyword, ignoreCase = true) ||
                model.providerName.contains(keyword, ignoreCase = true) ||
                (model.providerId == FREE_PROVIDER_ID && freeProviderName.contains(keyword, ignoreCase = true))
        }
    }
    Box {
        // A soft pill behind the current model makes it read as a tappable selector, not plain text.
        // Kept compact so it shares the top bar comfortably with the menu + mode island.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .clickable {
                    searchQuery = ""
                    expanded = true
                }
                .padding(start = 12.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Text(
                text = modelLabel ?: stringResource(R.string.select_model),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 132.dp),
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = stringResource(R.string.cd_switch_model),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // Fixed width + Pixel-style rounded corners; the model list scrolls inside a capped height
            // so the menu never runs off the bottom of the screen and the Provider row shows.
            modifier = Modifier
                .clip(ConeShapes.Menu)
                .width(280.dp),
        ) {
            if (models.isEmpty()) {
                DropdownMenuItem(
                    text = { Text(noModels) },
                    onClick = { expanded = false; onOpenProviders() },
                )
            } else {
                val keyboard = LocalSoftwareKeyboardController.current
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(searchModels) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = clearSearch)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                    shape = ConeShapes.Field,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                val modelScroll = rememberScrollState()
                LaunchedEffect(searchQuery) { modelScroll.scrollTo(0) }
                Column(
                    modifier = Modifier
                        .heightIn(max = 300.dp)
                        .verticalScroll(modelScroll),
                ) {
                    if (matches.isEmpty()) {
                        Text(
                            text = noMatches,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                    matches.forEach { m ->
                        val selected = m.providerId == activeProviderId && m.modelId == activeModelId
                        val isFree = m.providerId == FREE_PROVIDER_ID
                        DropdownMenuItem(
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                ),
                            text = {
                                Column {
                                    Text(
                                        m.displayName.ifBlank { m.modelId },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (isFree) freeProviderName else m.providerName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            },
                            trailingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else {
                                null
                            },
                            onClick = { expanded = false; onSelect(m.providerId, m.modelId) },
                        )
                    }
                }
                // Pinned outside the scroll area so it stays visible however long the list is.
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(manageProviders) },
                    onClick = { expanded = false; onOpenProviders() },
                )
            }
        }
    }
}
