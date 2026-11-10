package com.ljyh.mei.ui.screen.main.home

import androidx.compose.runtime.Composable
import com.ljyh.mei.ui.screen.source.SourceFeedScreen
import com.ljyh.mei.source.api.FeedKind

@Composable
fun HomeScreen() = SourceFeedScreen("首页", FeedKind.HOME)
