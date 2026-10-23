package com.ljyh.mei.ui.screen.main.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.kyant.capsule.ContinuousRoundedRectangle
import com.ljyh.mei.constants.HomeSectionSpacing
import com.ljyh.mei.constants.HomeSectionTitlePadding
import com.ljyh.mei.constants.HomeCardSpacing
import com.ljyh.mei.constants.HomeRowHorizontalPadding
import com.ljyh.mei.constants.homePlaylistCardSize
import com.ljyh.mei.constants.RecommendCardCornerRadius
import com.ljyh.mei.constants.RecommendCardHeight
import com.ljyh.mei.constants.RecommendCardHeightTablet
import com.ljyh.mei.constants.RecommendCardWidth
import com.ljyh.mei.constants.RecommendCardWidthTablet
import com.ljyh.mei.ui.component.shimmer.ShimmerHost
import com.ljyh.mei.ui.component.home.homeArtworkBorder
import com.ljyh.mei.ui.component.utils.rememberDeviceInfo

private val shimmerColor @Composable get() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
private val cardShape = RoundedCornerShape(8.dp)

@Composable
fun HomeShimmer(contentPadding: PaddingValues) {
    val device = rememberDeviceInfo()
    val recommendCardWidth = if (device.isTablet) RecommendCardWidthTablet else RecommendCardWidth
    val recommendCardHeight = if (device.isTablet) RecommendCardHeightTablet else RecommendCardHeight

    ShimmerHost {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(HomeSectionSpacing),
            contentPadding = contentPadding
        ) {
            item {
                RecommendRowShimmer(
                    cardWidth = recommendCardWidth,
                    cardHeight = recommendCardHeight,
                    count = 3
                )
            }
            repeat(3) {
                item {
                    PlaylistBlockShimmer(
                        count = 4
                    )
                }
            }
        }
    }
}

@Composable
private fun RecommendRowShimmer(
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    count: Int = 3
) {
    Column {
        TitleShimmer()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
            contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding)
        ) {
            items(count) {
                RecommendCardShimmer(cardWidth, cardHeight)
            }
        }
    }
}

@Composable
private fun RecommendCardShimmer(
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(cardWidth, cardHeight)
            .clip(ContinuousRoundedRectangle(RecommendCardCornerRadius))
            .background(shimmerColor)
    )
}

@Composable
private fun PlaylistBlockShimmer(
    count: Int = 4
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardSize = homePlaylistCardSize(maxWidth)
        Column {
            TitleShimmer()
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(HomeCardSpacing),
                contentPadding = PaddingValues(horizontal = HomeRowHorizontalPadding)
            ) {
                items(count) {
                    PlaylistCardShimmer(cardSize)
                }
            }
        }
    }
}

@Composable
private fun PlaylistCardShimmer(
    cardSize: androidx.compose.ui.unit.Dp
) {
    Column(
        modifier = Modifier
            .width(cardSize)
            .clip(cardShape)
    ) {
        Box(
            modifier = Modifier
                .size(cardSize)
                .background(shimmerColor)
                .homeArtworkBorder(cardShape)
        )
        Spacer(Modifier.height(8.dp))
        Column(modifier = Modifier.padding(horizontal = 2.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.8f)
                    .height(14.dp)
                    .background(shimmerColor)
            )
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .background(shimmerColor)
            )
        }
    }
}

@Composable
private fun TitleShimmer() {
    Box(
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = HomeSectionTitlePadding)
            .width(80.dp)
            .height(20.dp)
            .background(shimmerColor)
    )
}
