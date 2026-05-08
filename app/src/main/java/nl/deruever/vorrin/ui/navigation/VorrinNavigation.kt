package nl.deruever.vorrin.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

import nl.deruever.vorrin.ui.library.LibraryScreen
import nl.deruever.vorrin.ui.library.LibraryViewModel
import nl.deruever.vorrin.ui.player.PlayerScreen

@Serializable
object LibraryRoute

@Serializable
data class PlayerRoute(val bookId: String)

@Composable
fun VorrinNavigation() {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()

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
                onBookClick = { book ->
                    libraryViewModel.setActiveBook(book)
                    navController.navigate(PlayerRoute(bookId = book.id))
                }
            )
        }

        composable<PlayerRoute> { backStackEntry ->
            val args = backStackEntry.toRoute<PlayerRoute>()

            val book = libraryViewModel.books.value.find { it.id == args.bookId }
                ?: nl.deruever.vorrin.data.FakeData.books.find { it.id == args.bookId }
                ?: nl.deruever.vorrin.data.FakeData.books[0]

            PlayerScreen(
                book = book,
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
