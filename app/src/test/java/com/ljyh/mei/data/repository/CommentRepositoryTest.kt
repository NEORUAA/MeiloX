package com.ljyh.mei.data.repository

import com.ljyh.mei.ui.screen.comment.CommentComposerState
import com.ljyh.mei.ui.screen.comment.CommentLikeState
import org.junit.Assert.*
import org.junit.Test

class CommentRepositoryTest {
    @Test
    fun composerAndAcceptedLikeStatesPreventInvalidActions() {
        assertFalse(CommentComposerState(draft = " \n ").canSend)
        assertFalse(CommentComposerState(draft = "Comment", isSending = true).canSend)
        assertTrue(CommentComposerState(draft = "Comment").canSend)
        assertEquals(CommentLikeState(true, 11), CommentLikeState(false, 10).toggled())
        assertEquals(CommentLikeState(false, 10), CommentLikeState(false, 10).toggled().toggled())
        assertEquals(CommentLikeState(false, 0), CommentLikeState(true, 0).toggled())
    }

}
