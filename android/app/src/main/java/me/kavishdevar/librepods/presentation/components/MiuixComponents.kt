/* LibrePods Hyper contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared adapters keep device controls on the same MIUIx surfaces and semantics. */
@Composable
internal fun MiuixSettingsList(
    modifier: Modifier = Modifier,
    title: String? = null,
    description: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = modifier) {
        title?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
        }
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = 24.dp, insideMargin = PaddingValues(0.dp)) {
            content()
        }
        description?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
internal fun MiuixToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    BasicComponent(
        title = label,
        summary = description,
        enabled = enabled,
        role = Role.Switch,
        modifier = Modifier.semantics {
            toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
        },
        onClick = { onCheckedChange(!checked) },
        endActions = {
            // The whole row is the single toggle target, including accessibility.
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
    )
}

@Composable
internal fun MiuixSettingsRow(
    modifier: Modifier,
    name: String,
    description: String?,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    selected: Boolean?,
    orientation: ListItemOrientation,
    leadingContent: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?
) {
    BasicComponent(
        modifier = modifier.then(
            if (selected != null) Modifier.semantics { this.selected = selected } else Modifier
        ),
        title = name,
        summary = description,
        enabled = enabled,
        onClick = onClick,
        role = if (selected != null) Role.RadioButton else null,
        startAction = leadingContent,
        bottomAction = if (orientation == ListItemOrientation.Vertical) trailingContent else null,
        endActions = {
            when {
                selected != null -> RadioButton(selected = selected, onClick = null, enabled = enabled)
                trailingContent != null && orientation == ListItemOrientation.Horizontal -> trailingContent()
                onClick != null -> Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions
                )
            }
        }
    )
}

@Composable
internal fun MiuixSettingsSlider(
    label: String?,
    description: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    snapPoints: List<Float>,
    snapThreshold: Float,
    startLabel: String?,
    endLabel: String?,
    startIcon: String?,
    endIcon: String?,
    enabled: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        label?.let {
            Text(text = it, style = MiuixTheme.textStyles.main)
            Spacer(modifier = Modifier.height(12.dp))
        }
        Slider(
            value = value,
            valueRange = valueRange,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            keyPoints = snapPoints.takeIf { it.isNotEmpty() },
            showKeyPoints = snapPoints.isNotEmpty(),
            magnetThreshold = snapThreshold.coerceIn(0f, 1f)
        )
        if (startLabel != null || endLabel != null || startIcon != null || endIcon != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    startLabel ?: startIcon.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontFamily = if (startLabel == null && startIcon != null) FontFamily(Font(R.font.sf_pro)) else FontFamily.Default
                    )
                )
                Text(
                    endLabel ?: endIcon.orEmpty(),
                    style = MiuixTheme.textStyles.footnote1.copy(
                        fontFamily = if (endLabel == null && endIcon != null) FontFamily(Font(R.font.sf_pro)) else FontFamily.Default
                    )
                )
            }
        }
        description?.let {
            Text(
                text = it,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
internal fun MiuixScreenScaffold(
    modifier: Modifier,
    visible: Boolean,
    title: String,
    showBackButton: Boolean,
    onNavigateBack: () -> Unit,
    actionButtons: List<@Composable (LayerBackdrop) -> Unit>,
    snackbarHostState: SnackbarHostState,
    content: @Composable () -> Unit
) {
    Scaffold(
        containerColor = MiuixTheme.colorScheme.surface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (visible) {
                TopAppBar(
                    title = title,
                    navigationIcon = {
                        if (showBackButton) {
                            IconButton(onClick = onNavigateBack, modifier = Modifier.padding(start = 12.dp)) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.backward))
                            }
                        }
                    },
                    actions = {
                        actionButtons.forEach { it(rememberLayerBackdrop()) }
                    }
                )
            }
        }
    ) { padding ->
        Box(modifier = modifier.fillMaxSize().then(if (visible) Modifier.padding(padding) else Modifier)) {
            content()
        }
    }
}
