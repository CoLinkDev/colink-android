package com.colink.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.colink.android.ui.motion.sharedAxisPageEnterTransition
import com.colink.android.ui.motion.sharedAxisPageExitTransition

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState > initialState
            sharedAxisPageEnterTransition(forward) togetherWith
                sharedAxisPageExitTransition(forward)
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
