package com.dtv.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtv.androidtv.data.PlatformCatalog
import com.dtv.androidtv.data.model.LivePlatform
import com.dtv.androidtv.ui.browse.BrowseScreen
import com.dtv.androidtv.ui.player.PlayerScreen

private object Routes {
    const val BROWSE = "browse"
    const val PLAYER = "player/{platform}/{roomId}?nickname={nickname}"
}

@Composable
fun DtvTvNav(
    catalog: PlatformCatalog,
    modifier: Modifier = Modifier,
) {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = Routes.BROWSE,
        modifier = modifier,
    ) {
        composable(Routes.BROWSE) {
            BrowseScreen(
                catalog = catalog,
                onOpenRoom = { room ->
                    val platform = room.platform.ifBlank { catalog.active.id }
                    nav.navigate(
                        "player/$platform/${room.id}?nickname=${android.net.Uri.encode(room.nickname)}",
                    )
                },
                onPlayTest = {
                    nav.navigate(
                        "player/${LivePlatform.DOUYU.id}/test?nickname=${android.net.Uri.encode("测试流")}",
                    )
                },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("platform") { type = NavType.StringType },
                navArgument("roomId") { type = NavType.StringType },
                navArgument("nickname") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) { entry ->
            val platform = entry.arguments?.getString("platform").orEmpty()
            val roomId = entry.arguments?.getString("roomId").orEmpty()
            val nickname = entry.arguments?.getString("nickname").orEmpty()
            PlayerScreen(
                api = catalog.apiFor(platform),
                platformId = platform.ifBlank { LivePlatform.DOUYU.id },
                roomId = roomId,
                fallbackNickname = nickname,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
