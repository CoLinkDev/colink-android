package com.colink.android.ui.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

private const val PageTransitionDurationMillis = 300
private const val PageFadeProgressThreshold = 0.35f
private val PageFadeOutDurationMillis =
    (PageTransitionDurationMillis * PageFadeProgressThreshold).toInt()
private val PageFadeInDurationMillis =
    PageTransitionDurationMillis - PageFadeOutDurationMillis
private const val PageOffsetFactor = 0.1f

fun sharedAxisPageEnterTransition(forward: Boolean): EnterTransition =
    slideInHorizontally(
        animationSpec = tween(
            durationMillis = PageTransitionDurationMillis,
            easing = FastOutSlowInEasing,
        ),
        initialOffsetX = { width ->
            val offset = (width * PageOffsetFactor).toInt()
            if (forward) offset else -offset
        },
    ) + fadeIn(
        animationSpec = tween(
            durationMillis = PageFadeInDurationMillis,
            delayMillis = PageFadeOutDurationMillis,
            easing = LinearOutSlowInEasing,
        ),
    )

fun sharedAxisPageExitTransition(forward: Boolean): ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(
            durationMillis = PageTransitionDurationMillis,
            easing = FastOutSlowInEasing,
        ),
        targetOffsetX = { width ->
            val offset = (width * PageOffsetFactor).toInt()
            if (forward) -offset else offset
        },
    ) + fadeOut(
        animationSpec = tween(
            durationMillis = PageFadeOutDurationMillis,
            easing = FastOutLinearInEasing,
        ),
    )
