package dev.zig.notificationfilter.ui.review

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.zig.notificationfilter.R

@Composable
fun NotificationReviewRoute(modifier: Modifier = Modifier) {
    val viewModel: NotificationReviewViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val archiveUiState by viewModel.archiveUiState.collectAsState()

    var showArchive by remember { mutableStateOf(false) }

    NotificationReviewScreen(
        uiState = uiState,
        archiveUiState = archiveUiState,
        showArchive = showArchive,
        onToggleArchive = { showArchive = !showArchive },
        onAllowClicked = viewModel::onAllowClicked,
        onBlockClicked = viewModel::onBlockClicked,
        onUndoClicked = viewModel::onUndoClicked,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationReviewScreen(
    uiState: ReviewUiState,
    archiveUiState: ReviewUiState,
    showArchive: Boolean,
    onToggleArchive: () -> Unit,
    onAllowClicked: (Long) -> Unit,
    onBlockClicked: (Long) -> Unit,
    onUndoClicked: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = if (showArchive) "Archive" else "Notifications",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = onToggleArchive) {
                        if (showArchive) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "View active notifications",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "View archive",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val activeState = if (showArchive) archiveUiState else uiState

        when (activeState) {
            is ReviewUiState.Loading -> {
                // Room has not yet emitted — intentionally blank to avoid flicker.
            }
            is ReviewUiState.Empty -> {
                EmptyReviewState(
                    message = if (showArchive) "Archive is empty." else "You're all caught up!",
                    modifier = Modifier.padding(innerPadding),
                )
            }
            is ReviewUiState.Content -> {
                // TODO: NotificationReviewList(groups = activeState.groups, ...)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${activeState.groups.size} app(s) · " +
                            "${activeState.groups.values.sumOf { it.size }} notification(s)",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyReviewState(
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // zig_logo is in drawable-nodpi — use with alpha to keep the empty state subtle.
        Image(
            painter = painterResource(id = R.drawable.zig_logo),
            contentDescription = null,
            modifier = Modifier
                .size(120.dp)
                .alpha(0.25f),
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
