package com.ljyh.mei.ui.screen.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ljyh.mei.R
import com.ljyh.mei.ui.glass.IosTypography
import com.ljyh.mei.ui.glass.LocalGlassColors

internal const val ArtistCatalogSearchPageBudget = 3
internal const val ArtistCatalogSearchDebounceMillis = 250L

@Composable
internal fun ArtistCatalogSearchStatus(
    checkedCount: Int,
    matchCount: Int,
    isLoading: Boolean,
    hasMore: Boolean,
) {
    val status = when {
        isLoading -> R.string.artist_catalog_search_progress
        hasMore -> R.string.artist_catalog_search_partial
        else -> R.string.artist_catalog_search_complete
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = LocalGlassColors.current.accent,
                strokeWidth = 2.dp,
            )
        }
        Text(
            text = stringResource(status, matchCount, checkedCount),
            style = IosTypography.subheadline,
            color = LocalGlassColors.current.secondaryContent,
        )
    }
}
