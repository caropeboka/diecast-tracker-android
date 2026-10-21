package io.github.caropeboka.diecasttracker.ui

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.ui.theme.Garage

/** The main screens, one tap apart; the app opens on the collection. */
enum class MainTab(val label: String, @DrawableRes val icon: Int) {
    Collection("Koleksi", R.drawable.ic_car),
    Garage("Garasi", R.drawable.ic_dashboard),
    Explore("Peta", R.drawable.ic_map),
    Dex("Dex", R.drawable.ic_collections_bookmark),
}

@Composable
fun MainNavigationBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = Garage.Card) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onSelect(tab) },
                icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Garage.Brand,
                    selectedTextColor = Garage.Foreground,
                    indicatorColor = Garage.Brand.copy(alpha = 0.14f),
                    unselectedIconColor = Garage.MutedForeground,
                    unselectedTextColor = Garage.MutedForeground,
                ),
            )
        }
    }
}
