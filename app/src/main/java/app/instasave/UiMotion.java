package app.instasave;

import android.animation.ValueAnimator;
import android.view.View;

/** Short, optional transitions for content that appears after a user action. */
final class UiMotion {
    private UiMotion() { }

    static void reveal(View view, int distanceDp, long delayMs) {
        view.animate().cancel();
        view.setAlpha(1f);
        view.setTranslationY(0f);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0f);
        view.setTranslationY(distanceDp * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0f).setStartDelay(delayMs).setDuration(260).start();
    }

    static void fadeIn(View view) {
        view.animate().cancel();
        view.setAlpha(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.setAlpha(0f);
        view.animate().alpha(1f).setStartDelay(0).setDuration(200).start();
    }

    static void selection(View view, boolean selected) {
        view.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            view.setScaleX(selected ? 1f : 0.96f);
            view.setScaleY(selected ? 1f : 0.96f);
            view.setAlpha(selected ? 1f : 0.62f);
            return;
        }
        view.animate().scaleX(selected ? 1f : 0.96f).scaleY(selected ? 1f : 0.96f)
                .alpha(selected ? 1f : 0.62f).setStartDelay(0).setDuration(180).start();
    }

    static void tap(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().scaleX(0.97f).scaleY(0.97f).setStartDelay(0).setDuration(80)
                .withEndAction(() -> view.animate().scaleX(1f).scaleY(1f)
                        .setStartDelay(0).setDuration(140).start()).start();
    }
}
