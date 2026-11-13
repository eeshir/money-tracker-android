package com.pennywiseai.tracker.data.refunds

import androidx.compose.runtime.staticCompositionLocalOf
import java.math.BigDecimal

/**
 * What each refund has linked to expenses so far (refund id to amount), so a refund's row in any list can say how much of
 * it is still floating. Provided once in MainScreen; empty where it is not provided.
 */
val LocalRefundLinked = staticCompositionLocalOf<Map<Long, BigDecimal>> { emptyMap() }
