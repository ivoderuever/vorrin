package nl.deruever.vorrin.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import nl.deruever.vorrin.MainActivity
import nl.deruever.vorrin.ui.library.LibraryScreen
import nl.deruever.vorrin.ui.library.LibraryViewModel
import nl.deruever.vorrin.ui.player.PlayerScreen
import nl.deruever.vorrin.ui.player.PlayerViewModel

@Serializable
object LibraryRoute

@Serializable
data class PlayerRoute(val bookId: String)

@Composable
fun VorrinNavigation() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()

    val activity = LocalContext.current as MainActivity
    val openPlayer by activity.openPlayerEvent.collectAsState()
    val activeBook by libraryViewModel.activeBook.collectAsState()

    // Pre-connect as soon as the active book is known so the mini player and
    // player screen are ready without waiting for navigation to trigger it.
    LaunchedEffect(activeBook) {
        activeBook?.let { playerViewModel.connect(it) }
    }

    LaunchedEffect(openPlayer) {
        if (openPlayer) {
            val activeBook = libraryViewModel.activeBook.value
            if (activeBook != null) {
                navController.navigate(PlayerRoute(bookId = activeBook.id)) {
                    launchSingleTop = true
                }
            }
            activity.consumeOpenPlayerEvent()
        }
    }

    NavHost(
        navController = navController,
        startDestination = LibraryRoute,
        enterTransition = { slideInHorizontally { it } + fadeIn() },
        exitTransition = { slideOutHorizontally { -it } + fadeOut() },
        popEnterTransition = { slideInHorizontally { -it } + fadeIn() },
        popExitTransition = { slideOutHorizontally { it } + fadeOut() },
    ) {
        composable<LibraryRoute> {
            LibraryScreen(
                viewModel = libraryViewModel,
                playerViewModel = playerViewModel,
                onBookClick = { book ->
                    if (book.isFinished) playerViewModel.resetProgress(book)
                    libraryViewModel.setActiveBook(book)
                    navController.navigate(PlayerRoute(bookId = book.id))
                }
            )
        }

        composable<PlayerRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<PlayerRoute>()

            val books by libraryViewModel.books.collectAsState()
            val book = books.find { it.id == args.bookId }

            if (book == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            } else {
                PlayerScreen(
                    book = book,
                    playerViewModel = playerViewModel,
                    onBackClick = { navController.popBackStack() }
                )
            }
        }
    }
}
