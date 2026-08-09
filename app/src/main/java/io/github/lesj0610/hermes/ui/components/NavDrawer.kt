package io.github.lesj0610.hermes.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.lesj0610.hermes.ui.theme.LocalRunColors

/**
 * The three-line menu affordance, drawn rather than imported.
 *
 * Material's icon artifacts are a separate dependency, and this app needs
 * exactly one glyph from them. Three rounded bars cost nothing and match the
 * launcher mark's geometry.
 */
@Composable
fun HamburgerIcon(modifier: Modifier = Modifier) {
    val colors = LocalRunColors.current
    Column(
        modifier.size(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        repeat(3) { index ->
            Box(
                Modifier
                    .width(18.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(colors.muted),
            )
            if (index < 2) Box(Modifier.height(4.dp))
        }
    }
}

/** Alias so callers do not need a foundation import just for a spacer box. */
@Composable
private fun Box(modifier: Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {}
}

/** One destination in the phone navigation drawer. */
@Composable
fun DrawerDestination(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalRunColors.current
    NavigationDrawerItem(
        label = { Text(label, style = MaterialTheme.typography.bodyMedium) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        colors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colors.panelRaised,
            unselectedContainerColor = MaterialTheme.colorScheme.background,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Section label inside the drawer. */
@Composable
fun DrawerSection(title: String) {
    val colors = LocalRunColors.current
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
}
