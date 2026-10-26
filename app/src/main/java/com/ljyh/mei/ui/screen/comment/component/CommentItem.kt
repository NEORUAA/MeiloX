package com.ljyh.mei.ui.screen.comment.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ljyh.mei.R
import com.ljyh.mei.data.model.weapi.CommentX
import com.ljyh.mei.data.model.weapi.FComment
import com.ljyh.mei.data.network.Resource
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.icons.SfIcon
import com.ljyh.mei.ui.screen.comment.CommentLikeState
import com.ljyh.mei.ui.screen.comment.CommentReplyTarget
import java.text.NumberFormat

@Composable
fun CommentItem(
    comment: CommentX,
    isExpanded: Boolean,
    floorComments: Resource<List<FComment>>,
    likes: Map<Long, CommentLikeState>,
    pendingLikes: Set<Long>,
    onToggleFloor: (Long, Int) -> Unit,
    onRetryFloor: () -> Unit,
    onReply: (CommentReplyTarget) -> Unit,
    onLike: (Long, Boolean, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGlassColors.current
    val floorCount = comment.showFloorComment?.replyCount ?: comment.replyCount
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 16.dp, end = 20.dp)) {
            AsyncImage(
                model = comment.user.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(36.dp).clip(CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                CommentIdentity(comment.user.nickname, comment.timeStr)
                Spacer(Modifier.height(6.dp))
                Text(
                    comment.content,
                    style = IosTypography.subheadline.copy(fontSize = 15.sp, lineHeight = 22.sp),
                    color = colors.content,
                )
                CommentActions(
                    state = likes[comment.commentId] ?: CommentLikeState(comment.liked, comment.likedCount),
                    isPending = comment.commentId in pendingLikes,
                    onReply = { onReply(CommentReplyTarget(comment.commentId, comment.commentId, comment.user.nickname, comment.content)) },
                    onLike = { onLike(comment.commentId, comment.liked, comment.likedCount) },
                    repliesLabel = if (floorCount > 0) stringResource(
                        if (isExpanded) R.string.comment_hide_replies else R.string.comment_show_replies,
                        floorCount,
                    ) else null,
                    onToggleReplies = { onToggleFloor(comment.commentId, floorCount) },
                )
            }
        }
        AnimatedVisibility(isExpanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 66.dp, end = 20.dp, bottom = 10.dp)
                    .drawBehind {
                        drawLine(colors.separator, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                    },
            ) {
                when (floorComments) {
                    Resource.Loading -> CircularProgressIndicator(
                        modifier = Modifier.padding(16.dp).size(16.dp),
                        strokeWidth = 1.5.dp,
                        color = colors.secondaryContent,
                    )
                    is Resource.Success -> floorComments.data.forEach { floor ->
                        FloorCommentItem(
                            comment = floor,
                            likeState = likes[floor.commentId] ?: CommentLikeState(floor.liked, floor.likedCount),
                            isLikePending = floor.commentId in pendingLikes,
                            onReply = { onReply(CommentReplyTarget(floor.commentId, comment.commentId, floor.user.nickname, floor.content)) },
                            onLike = { onLike(floor.commentId, floor.liked, floor.likedCount) },
                        )
                    }
                    is Resource.Error -> Column(Modifier.padding(start = 14.dp, top = 8.dp)) {
                        Text(stringResource(R.string.load_failed), style = IosTypography.caption, color = colors.secondaryContent)
                        CommentTextAction(stringResource(R.string.comment_retry), onRetryFloor)
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = 66.dp, end = 20.dp),
            thickness = 0.5.dp,
            color = colors.separator,
        )
    }
}

@Composable
internal fun CommentIdentity(nickname: String, time: String) {
    val colors = LocalGlassColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            nickname,
            modifier = Modifier.weight(1f),
            style = IosTypography.subheadline.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            color = colors.content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(time, style = IosTypography.caption.copy(fontSize = 12.sp), color = colors.tertiaryContent, maxLines = 1)
    }
}

@Composable
internal fun CommentActions(
    state: CommentLikeState,
    isPending: Boolean,
    onReply: () -> Unit,
    onLike: () -> Unit,
    repliesLabel: String? = null,
    onToggleReplies: () -> Unit = {},
) {
    val colors = LocalGlassColors.current
    val likeLabel = stringResource(if (state.liked) R.string.comment_unlike else R.string.comment_like)
    val likeState = stringResource(if (state.liked) R.string.comment_liked else R.string.comment_not_liked)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CommentTextAction(stringResource(R.string.comment_reply), onReply)
        if (repliesLabel != null) {
            Text("·", style = IosTypography.caption, color = colors.tertiaryContent, modifier = Modifier.padding(end = 6.dp))
            CommentTextAction(repliesLabel, onToggleReplies, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Row(
            modifier = Modifier
                .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
                .graphicsLayer { alpha = if (pressed) 0.45f else 1f }
                .clickable(
                    enabled = !isPending,
                    interactionSource = interactionSource,
                    indication = null,
                    role = Role.Button,
                    onClick = onLike,
                )
                .semantics {
                    contentDescription = likeLabel
                    stateDescription = likeState
                },
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isPending) {
                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 1.5.dp, color = colors.secondaryContent)
            } else {
                SfIcon(
                    if (state.liked) "heart.fill" else "heart",
                    contentDescription = null,
                    size = 16.dp,
                    tint = if (state.liked) colors.accent else colors.secondaryContent,
                )
            }
            if (state.count > 0) {
                Text(
                    NumberFormat.getIntegerInstance().format(state.count),
                    style = IosTypography.caption.copy(fontSize = 12.sp),
                    color = if (state.liked) colors.accent else colors.secondaryContent,
                )
            }
        }
    }
}

@Composable
internal fun CommentTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Box(
        modifier = modifier
            .sizeIn(minWidth = 44.dp, minHeight = 44.dp)
            .graphicsLayer { alpha = if (pressed) 0.45f else 1f }
            .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text,
            style = IosTypography.caption.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = LocalGlassColors.current.secondaryContent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
