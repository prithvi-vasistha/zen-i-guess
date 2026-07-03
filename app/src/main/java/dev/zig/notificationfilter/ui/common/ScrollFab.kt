package dev.zig.notificationfilter.ui.common

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.zig.notificationfilter.ui.theme.ZigGreen
import kotlinx.coroutines.launch

@Composable
fun ScrollFab(
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val totalItems by remember { derivedStateOf { listState.layoutInfo.totalItemsCount } }
    val scope = rememberCoroutineScope()
    val isAtTop by remember { derivedStateOf { listState.firstVisibleItemIndex <= 2 } }

    if (totalItems >= 10) {
        SmallFloatingActionButton(
            onClick = {
                scope.launch {
                    val total = listState.layoutInfo.totalItemsCount
                    if (isAtTop) listState.animateScrollToItem(total - 1)
                    else listState.animateScrollToItem(0)
                }
            },
            modifier = modifier,
            containerColor = ZigGreen,
            contentColor = Color.Black,
        ) {
            Icon(
                imageVector = if (isAtTop) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isAtTop) "Scroll to bottom" else "Scroll to top",
            )
        }
    }
}
