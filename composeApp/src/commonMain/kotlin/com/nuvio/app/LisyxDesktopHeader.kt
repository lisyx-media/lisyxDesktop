package com.nuvio.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_icon_original_transparent
import nuvio.composeapp.generated.resources.compose_nav_films
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.lisy_tv
import nuvio.composeapp.generated.resources.compose_nav_series
import nuvio.composeapp.generated.resources.lisyx_brand_name
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

internal val LisyxDesktopHeaderHeight = 64.dp

@Composable
internal fun LisyxDesktopHeader(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    onAddProfileRequested: () -> Unit,
    titleBarInset: Dp,
    scrolled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val opacity by animateFloatAsState(if (scrolled) 1f else 0f, tween(220), label = "header_opacity")
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().background(
            Brush.verticalGradient(listOf(Color(0xFF0D0D0D), Color(0xFF0D0D0D).copy(alpha = .7f + .3f * opacity), Color(0xFF0D0D0D).copy(alpha = opacity))),
        ),
    ) {
        val compact = maxWidth < 560.dp
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(top = titleBarInset)
                .height(LisyxDesktopHeaderHeight)
                .padding(horizontal = if (compact) 16.dp else (maxWidth * .045f).coerceIn(24.dp, 80.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (compact) "L" else "LISYX",
                fontSize = if (compact) 28.sp else 25.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Black,
                color = Color(0xFFE50914),
            )
            Spacer(Modifier.width(if (compact) 8.dp else 24.dp))
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val browseTabs = listOf(
                    AppScreenTab.Home to Res.string.compose_nav_home,
                    AppScreenTab.Films to Res.string.compose_nav_films,
                    AppScreenTab.Series to Res.string.compose_nav_series,
                    AppScreenTab.Tv to Res.string.lisy_tv,
                )
                browseTabs.forEach { (tab, label) ->
                    val selected = tab == selectedTab
                    Box(
                        modifier = Modifier.height(42.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .selectable(selected = selected, role = Role.Tab, onClick = { onTabSelected(tab) })
                            .padding(horizontal = if (compact) 8.dp else 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(label),
                            color = if (selected) Color.White else Color(0xFFC9C9CE),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            softWrap = false,
                        )

                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val utilityTabs = listOf(
                    Triple(AppScreenTab.Search, Res.string.compose_nav_search, Res.drawable.sidebar_search),
                    Triple(AppScreenTab.Library, Res.string.compose_nav_library, Res.drawable.sidebar_library),
                )
                utilityTabs.forEach { (tab, label, icon) ->
                    val selected = tab == selectedTab
                    Box(
                        modifier = Modifier.size(if (compact) 34.dp else 42.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) Color(0xFF303036) else Color.Transparent)
                            .selectable(selected = selected, role = Role.Tab, onClick = { onTabSelected(tab) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(icon),
                            contentDescription = stringResource(label),
                            tint = if (selected) Color.White else Color(0xFFC9C9CE),
                            modifier = Modifier.size(21.dp),
                        )
                    }
                }
                ProfileSwitcherTab(
                    selected = selectedTab == AppScreenTab.Settings,
                    onClick = { onTabSelected(AppScreenTab.Settings) },
                    onProfileSelected = onProfileSelected,
                    onAddProfileRequested = onAddProfileRequested,
                    popupBelowAnchor = true,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}
