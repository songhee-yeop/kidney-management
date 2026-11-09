package kr.songhy.kidney

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kr.songhy.kidney.data.Store
import kr.songhy.kidney.notify.Notifier
import kr.songhy.kidney.notify.Scheduler
import kr.songhy.kidney.ui.AppRoot
import kr.songhy.kidney.ui.KidneyTheme

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Notifier.createChannels(this)
    }
}

/** 화면이 다시 보일 때(권한 설정에서 돌아올 때 등) 상태를 다시 읽게 하는 신호 */
object UiBus {
    val resumeTick = mutableIntStateOf(0)
    val sharedText = mutableStateOf<String?>(null)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        setContent { KidneyTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        Scheduler.sync(this)
        UiBus.resumeTick.intValue++
    }

    private fun handleShare(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            i.getStringExtra(Intent.EXTRA_TEXT)?.let { UiBus.sharedText.value = it }
        }
    }
}
