package com.dtv.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dtv.androidtv.data.DtvApi
import com.dtv.androidtv.ui.browse.BrowseScreen
import com.dtv.androidtv.ui.player.PlayerScreen

private object Routes {
    const val BROWSE = "browse"
    const val PLAYER = "player/{roomId}?nickname={nickname}"
}

@Composable
fun DtvTvNav(
    api: DtvApi,
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
                api = api,
                onOpenRoom = { room ->
                    nav.navigate(
                        "player/${room.id}?nickname=${android.net.Uri.encode(room.nickname)}",
                    )
                },
                onPlayTest = {
                    nav.navigate("player/test?nickname=${android.net.Uri.encode("测试流")}")
                },
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("roomId") { type = NavType.StringType },
                navArgument("nickname") {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) { entry ->
            val roomId = entry.arguments?.getString("roomId").orEmpty()
            val nickname = entry.arguments?.getString("nickname").orEmpty()
            PlayerScreen(
                api = api,
                roomId = roomId,
                fallbackNickname = nickname,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
