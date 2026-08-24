package com.colink.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private val OnboardingPageEasing = CubicBezierEasing(0.5f, 0f, 0f, 1f)
private const val OnboardingPageTransitionDurationMillis = 420

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState > initialState) {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(
                        durationMillis = OnboardingPageTransitionDurationMillis,
                        easing = OnboardingPageEasing,
                    ),
                ) togetherWith slideOutHorizontally(
                    targetOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(
                        durationMillis = OnboardingPageTransitionDurationMillis,
                        easing = OnboardingPageEasing,
                    ),
                )
            } else {
                slideInHorizontally(
                    initialOffsetX = { fullWidth -> -fullWidth },
                    animationSpec = tween(
                        durationMillis = OnboardingPageTransitionDurationMillis,
                        easing = OnboardingPageEasing,
                    ),
                ) togetherWith slideOutHorizontally(
                    targetOffsetX = { fullWidth -> fullWidth },
                    animationSpec = tween(
                        durationMillis = OnboardingPageTransitionDurationMillis,
                        easing = OnboardingPageEasing,
                    ),
                )
            }
        },
        modifier = modifier,
        label = "onboarding_page",
    ) { currentPage ->
        when (currentPage) {
            0 -> IntroPage(onNext = { page = 1 })
            else -> PermissionsPage(onComplete = onComplete)
        }
    }
}
