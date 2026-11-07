package com.ljyh.mei.ui.screen.main.home

/** Clamp card-start snapping to the real scroll range, including the trailing content padding. */
internal fun homeRowSnapPosition(
    layoutSize: Int,
    itemSize: Int,
    beforeContentPadding: Int,
    afterContentPadding: Int,
    itemIndex: Int,
    itemCount: Int,
    itemSpacing: Int,
): Int {
    val stride = itemSize + itemSpacing
    val contentSize = itemCount * stride - itemSpacing + beforeContentPadding + afterContentPadding
    val maxScroll = (contentSize - layoutSize).coerceAtLeast(0)
    return (itemIndex * stride - maxScroll).coerceAtLeast(0)
}
