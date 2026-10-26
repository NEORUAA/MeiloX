package com.ljyh.mei.ui.screen.comment.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ljyh.mei.data.model.weapi.FComment
import io.github.neoruaa.glasmio.core.IosTypography
import io.github.neoruaa.glasmio.core.LocalGlassColors
import com.ljyh.mei.ui.screen.comment.CommentLikeState

@Composable
fun FloorCommentItem(
    comment: FComment,
    likeState: CommentLikeState,
    isLikePending: Boolean,
    onReply: () -> Unit,
    onLike: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(start = 14.dp, top = 8.dp)) {
        AsyncImage(
            model = comment.user.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(24.dp).clip(CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            CommentIdentity(comment.user.nickname, comment.timeStr)
            Spacer(Modifier.height(5.dp))
            Text(
                comment.content,
                style = IosTypography.subheadline.copy(fontSize = 14.sp, lineHeight = 21.sp),
                color = LocalGlassColors.current.content,
            )
            CommentActions(likeState, isLikePending, onReply, onLike)
        }
    }
}
