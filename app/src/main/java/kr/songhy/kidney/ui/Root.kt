package kr.songhy.kidney.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.delay
import kr.songhy.kidney.UiBus
import kr.songhy.kidney.data.Store
import java.time.LocalDateTime

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("홈", Icons.Filled.Home),
    Tab("검사", Icons.Filled.Info),
    Tab("혈압·체중", Icons.Filled.Favorite),
    Tab("일정", Icons.Filled.DateRange),
    Tab("설정", Icons.Filled.Settings),
)

@Composable
fun AppRoot() {
    val data by Store.flow.collectAsState()
    // 1분마다, 그리고 앱으로 돌아올 때마다 "지금" 갱신
    var minute by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } }
    val resume = UiBus.resumeTick.intValue
    val now = remember(minute, resume, data) { LocalDateTime.now() }

    if (data.profile == null) {
        OnboardingScreen()
        return
    }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pasteOpen by rememberSaveable { mutableStateOf(false) }
    val shared = UiBus.sharedText.value

    if (shared != null || pasteOpen) {
        PasteScreen(initial = shared ?: "", onClose = { pasteOpen = false; UiBus.sharedText.value = null })
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.label) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> HomeScreen(data, now, onPaste = { pasteOpen = true }, onGoLabs = { tab = 1 })
                1 -> LabsScreen(data, onPaste = { pasteOpen = true })
                2 -> VitalsScreen(data)
                3 -> ScheduleScreen(data, now)
                else -> SettingsScreen(data)
            }
        }
    }
}
