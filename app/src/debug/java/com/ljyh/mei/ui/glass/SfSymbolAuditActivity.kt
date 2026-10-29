package com.ljyh.mei.ui.glass

import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.defaultGlassColors
import io.github.neoruaa.glasmio.glass.LocalGlassBackdrop
import io.github.neoruaa.glasmio.icons.SfIcon
import io.github.neoruaa.glasmio.icons.SfSymbolCatalog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.neoruaa.glasmio.glass.render.backdrops.layerBackdrop
import io.github.neoruaa.glasmio.glass.render.backdrops.rememberLayerBackdrop
import com.ljyh.mei.ui.theme.MusicTheme

/** Debug-only visual fixture for checking every catalog glyph against a centered safe box. */
class SfSymbolAuditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MusicTheme(seedColor = Color(0xFF0088FF), isDark = false) {
                val backdrop = rememberLayerBackdrop()
                CompositionLocalProvider(
                    LocalGlassBackdrop provides backdrop,
                    LocalGlassColors provides defaultGlassColors(
                        isDark = false,
                        accent = Color(0xFF0088FF),
                    ),
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Color(0xFFF2F2F7))
                            .layerBackdrop(backdrop)
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp),
                    ) {
                        Text(
                            "SF Symbols · ${SfSymbolCatalog.systemNames.size}",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(72.dp),
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(SfSymbolCatalog.systemNames.sorted(), key = { it }) { name ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier
                                            .size(56.dp)
                                            .background(Color.White)
                                            .border(1.dp, Color(0x33000000))
                                            .drawBehind {
                                                drawLine(
                                                    Color(0x240088FF),
                                                    Offset(size.width / 2f, 0f),
                                                    Offset(size.width / 2f, size.height),
                                                )
                                                drawLine(
                                                    Color(0x240088FF),
                                                    Offset(0f, size.height / 2f),
                                                    Offset(size.width, size.height / 2f),
                                                )
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        SfIcon(name, null, size = 42.dp)
                                    }
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
