package com.ljyh.mei.ui.component.sheet

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import io.github.neoruaa.glasmio.scaffold.GlasmioBottomSheetState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs

internal data class MiniPlayerChromeSpring(
    val dampingRatio: Float,
    val response: Float,
) {
    fun animationSpec(): SpringSpec<Float> = spring(
        dampingRatio = dampingRatio,
        stiffness = (2.0 * PI / response).let { (it * it).toFloat() },
    )
}

internal data class MiniPlayerChromeFrame(
    val horizontalProgress: Float,
    val verticalProgress: Float,
)

internal data class MiniPlayerChromeTransition(
    val intermediate: MiniPlayerChromeFrame,
    val intermediateSpring: MiniPlayerChromeSpring,
    val finalSpring: MiniPlayerChromeSpring,
    val finalDelayMillis: Long,
)

/** UIKit's accessory layout uses model frames to choose its short-travel path. */
internal fun miniPlayerChromeTransition(
    compact: Boolean,
    currentModelFrame: MiniPlayerChromeFrame,
    centerTravel: Dp,
): MiniPlayerChromeTransition {
    val target = if (compact) 1f else 0f
    if (abs(target - currentModelFrame.verticalProgress) * abs(centerTravel.value) <= 15f) {
        val spring = MiniPlayerChromeSpring(dampingRatio = 1f, response = 0.3f)
        return MiniPlayerChromeTransition(MiniPlayerChromeFrame(target, target), spring, spring, 0)
    }
    return if (compact) {
        MiniPlayerChromeTransition(
            intermediate = MiniPlayerChromeFrame(target, currentModelFrame.verticalProgress),
            intermediateSpring = MiniPlayerChromeSpring(dampingRatio = 0.72f, response = 0.36f),
            finalSpring = MiniPlayerChromeSpring(dampingRatio = 0.76f, response = 0.42f),
            finalDelayMillis = 50,
        )
    } else {
        MiniPlayerChromeTransition(
            intermediate = MiniPlayerChromeFrame(currentModelFrame.horizontalProgress, target),
            intermediateSpring = MiniPlayerChromeSpring(dampingRatio = 0.72f, response = 0.30f),
            finalSpring = MiniPlayerChromeSpring(dampingRatio = 0.72f, response = 0.36f),
            finalDelayMillis = 50,
        )
    }
}

private class MiniPlayerChromeChannel(initialValue: Float) {
    val animation: Animatable<Float, AnimationVector1D> = Animatable(initialValue)
    private var carriedVelocity = 0f

    suspend fun animateTo(target: Float, spring: MiniPlayerChromeSpring) {
        // Keep the Compose trajectory continuous when a new stage or direction
        // interrupts it. UIKit's private animator owns its own retarget integration.
        animation.animateTo(target, spring.animationSpec(), initialVelocity = carriedVelocity) {
            carriedVelocity = velocity
        }
        carriedVelocity = 0f
    }

    suspend fun snapTo(target: Float) {
        carriedVelocity = 0f
        animation.snapTo(target)
    }
}

@Stable
internal class MiniPlayerChromeMotion(compact: Boolean) {
    private val initialProgress = if (compact) 1f else 0f
    private val horizontal = MiniPlayerChromeChannel(initialProgress)
    private val vertical = MiniPlayerChromeChannel(initialProgress)
    private val content = MiniPlayerChromeChannel(initialProgress)
    private var modelFrame = MiniPlayerChromeFrame(initialProgress, initialProgress)

    val horizontalProgress: State<Float> = horizontal.animation.asState()
    val verticalProgress: State<Float> = vertical.animation.asState()
    val contentProgress: State<Float> = content.animation.asState()

    internal suspend fun update(compact: Boolean, playerVisible: Boolean, centerTravel: Dp) {
        val target = if (compact) 1f else 0f
        if (playerVisible) {
            // A captured opening keeps its origin. The hidden mini player becomes
            // the settled return endpoint instead of replaying a cancelled morph.
            modelFrame = MiniPlayerChromeFrame(target, target)
            horizontal.snapTo(target)
            vertical.snapTo(target)
            content.snapTo(target)
            return
        }
        val transition = miniPlayerChromeTransition(compact, modelFrame, centerTravel)
        // UIKit executes both non-escaping geometry blocks in the transaction.
        // Its delay offsets presentation; the complete model target is committed now.
        modelFrame = MiniPlayerChromeFrame(target, target)
        coroutineScope {
            launch { horizontal.animateTo(transition.intermediate.horizontalProgress, transition.intermediateSpring) }
            launch { vertical.animateTo(transition.intermediate.verticalProgress, transition.intermediateSpring) }
            // The native environment changes in the size-changing layout transaction.
            // This independent layout channel adapts that transaction to Compose;
            // there is no additional placement fade or scale animation.
            launch { content.animateTo(target, transition.intermediateSpring) }
            if (transition.finalDelayMillis > 0) {
                delay(transition.finalDelayMillis)
                launch { horizontal.animateTo(target, transition.finalSpring) }
                launch { vertical.animateTo(target, transition.finalSpring) }
                launch { content.animateTo(target, transition.finalSpring) }
            }
        }
    }
}

internal val LocalMiniPlayerChromeMotion = staticCompositionLocalOf<MiniPlayerChromeMotion?> { null }

internal data class MiniPlayerChromeHint(
    val progress: State<Float>,
    val pivotFromTop: () -> Dp,
)

internal val LocalMiniPlayerChromeHint = staticCompositionLocalOf<MiniPlayerChromeHint?> { null }

@Composable
internal fun rememberMiniPlayerChromeMotion(
    compact: Boolean,
    sheet: GlasmioBottomSheetState,
    centerTravel: Dp,
): MiniPlayerChromeMotion {
    val motion = remember { MiniPlayerChromeMotion(compact) }
    val playerVisible by remember(sheet) { derivedStateOf { sheet.isContentVisible } }
    LaunchedEffect(compact, playerVisible, centerTravel) {
        motion.update(compact, playerVisible, centerTravel)
    }
    return motion
}
