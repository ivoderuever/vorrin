package nl.deruever.vorrin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import nl.deruever.vorrin.data.Audiobook
import nl.deruever.vorrin.ui.library.LibraryScreen
import nl.deruever.vorrin.ui.player.PlayerScreen
import nl.deruever.vorrin.ui.theme.VorrinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VorrinTheme {
//                LibraryScreen(onBookClick = { book: Audiobook ->
//                    // Player screen navigation comes later
//                })
                PlayerScreen()
            }
        }
    }
}