package com.ljyh.mei.ui.screen


import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.Alignment
import com.ljyh.mei.ui.navigation.MeiloXPinnedPage
import com.ljyh.mei.ui.local.LocalNavController
import com.ljyh.mei.ui.local.LocalPlayerAwareWindowInsets
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.kyant.capsule.ContinuousRoundedRectangle


@Composable
fun Test2(){
    AsyncImage(
        model = "https://p5.music.126.net/obj/wonDlsKUwrLClGjCm8Kx/33013382162/9706/80e4/105a/d1ac01d5ee46dbcad97051f0197c8b61.jpg?imageView=1&thumbnail=500y500",
        contentDescription = null,
        modifier = Modifier
            .requiredSize(60.dp)
            .clip(ContinuousRoundedRectangle(16.dp))
            .border(1.dp, Color.White, ContinuousRoundedRectangle(16.dp))

    )
}
@Composable
fun Test() {
    val navController = LocalNavController.current
    MeiloXPinnedPage(
        title = "Test",
        bottomPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues().calculateBottomPadding(),
        onNavigateBack = { navController.popBackStack() },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopStart) {
            Test2()
        }
    }
}
