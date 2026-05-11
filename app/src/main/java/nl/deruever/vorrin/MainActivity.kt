package nl.deruever.vorrin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.deruever.vorrin.ui.navigation.VorrinNavigation
import nl.deruever.vorrin.ui.theme.VorrinTheme

class MainActivity : ComponentActivity() {

    private val _openPlayerEvent = MutableStateFlow(false)
    val openPlayerEvent: StateFlow<Boolean> = _openPlayerEvent.asStateFlow()

    fun consumeOpenPlayerEvent() {
        _openPlayerEvent.value = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent?.getBooleanExtra("open_player", false) == true) {
            _openPlayerEvent.value = true
        }
        setContent {
            VorrinTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    VorrinNavigation()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_player", false)) {
            _openPlayerEvent.value = true
        }
    }
}
