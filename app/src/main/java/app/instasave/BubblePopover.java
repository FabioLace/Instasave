package app.instasave;

import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import static app.instasave.UiMotion.dp;

/**
 * Places the quick download card next to the floating bubble, as a speech bubble whose tail points
 * at it, and grows it out of the bubble and back into it.
 */
final class BubblePopover {
    // STYLE: speech bubble geometry and timing. The tail is drawable/bubble_tail (12×24dp).
    private static final int MARGIN_DP = 12;
    private static final int GAP_DP = 4;
    private static final int MAX_WIDTH_DP = 420;
    private static final int TAIL_WIDTH_DP = 12;
    private static final int TAIL_HEIGHT_DP = 24;
    /** The tail sits level with the thumbnail: the card's 16dp padding plus half of the 64dp thumbnail. */
    private static final int TAIL_CENTER_DP = 48;
    /** Space kept free at the bottom for the system's "pasted from your clipboard" toast. */
    private static final int TOAST_SPACE_DP = 96;
    private static final float HIDDEN_SCALE = 0.6f;

    private final View root;
    private final View scrim;
    private final View popover;
    private final View card;
    private final View tail;
    private final Rect anchorOnScreen;
    private Rect anchor;
    private boolean bubbleOnRight;
    /** Set once the horizontal layout is decided; the card's first layout may come before it. */
    private boolean placed;
    private boolean placedVertically;
    private boolean shown;
    /** The window is on screen: an animation started earlier would be over before it can be seen. */
    private boolean windowShown;
    private boolean dismissing;

    /** anchorOnScreen is the bubble's button in screen coordinates, or null to centre the card without a tail. */
    BubblePopover(View root, View scrim, View popover, View card, View tail, Rect anchorOnScreen) {
        this.root = root;
        this.scrim = scrim;
        this.popover = popover;
        this.card = card;
        this.tail = tail;
        this.anchorOnScreen = anchorOnScreen;
    }

    /** Positions the card once the screen is measured, then animates it in. */
    void show() {
        popover.setVisibility(View.INVISIBLE);
        scrim.setAlpha(0f);
        // Focus normally arrives within a few frames; never leave the card hidden if it doesn't.
        root.postDelayed(this::onWindowShown, 300);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                root.removeOnLayoutChangeListener(this);
                root.post(BubblePopover.this::placeBeside);
            }
        });
        // The card grows as the analysis fills it in, so its height is followed for the whole session.
        card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!shown || b - t != ob - ot) root.post(this::placeVertically);
        });
    }

    /** Called when the window gets focus, which only happens once it's visible; starts the entrance if placed. */
    void onWindowShown() {
        if (windowShown) return;
        windowShown = true;
        if (placedVertically) enter();
    }

    /** Shrinks the card back into the bubble, then runs then; immediately when animations are off. */
    void dismiss(Runnable then) {
        if (dismissing) return;
        dismissing = true;
        if (!shown || !ValueAnimator.areAnimatorsEnabled()) { then.run(); return; }
        popover.animate().cancel();
        scrim.animate().cancel();
        scrim.animate().alpha(0f).setDuration(180).start();
        popover.animate().alpha(0f).scaleX(HIDDEN_SCALE).scaleY(HIDDEN_SCALE).setDuration(180)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(then).start();
    }

    /** Horizontal layout: on the side of the bubble facing the screen, its tail just short of it. */
    private void placeBeside() {
        int[] origin = new int[2];
        root.getLocationOnScreen(origin);
        anchor = anchorOnScreen == null ? null : new Rect(anchorOnScreen);
        if (anchor != null) anchor.offset(-origin[0], -origin[1]);
        int width = root.getWidth();
        int margin = dp(root.getContext(), MARGIN_DP);
        FrameLayout.LayoutParams popoverParams = (FrameLayout.LayoutParams) popover.getLayoutParams();
        FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) card.getLayoutParams();
        if (anchor == null) {
            popoverParams.width = Math.min(width - 2 * margin, dp(root.getContext(), MAX_WIDTH_DP));
            popoverParams.gravity = Gravity.CENTER;
            cardParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
            tail.setVisibility(View.GONE);
        } else {
            bubbleOnRight = anchor.centerX() > width / 2;
            int gap = dp(root.getContext(), GAP_DP);
            int maxWidth = dp(root.getContext(), MAX_WIDTH_DP);
            int tipX = bubbleOnRight ? anchor.left - gap : anchor.right + gap;
            int left = bubbleOnRight ? Math.max(margin, tipX - maxWidth) : tipX;
            int right = bubbleOnRight ? tipX : Math.min(width - margin, tipX + maxWidth);
            popoverParams.width = right - left;
            popoverParams.leftMargin = left;
            popoverParams.gravity = Gravity.TOP | Gravity.START;
            // The tail overlaps the card by 1dp so its fill covers the card's border where they join.
            int tailWidth = dp(root.getContext(), TAIL_WIDTH_DP);
            cardParams.width = popoverParams.width - tailWidth + dp(root.getContext(), 1);
            cardParams.gravity = bubbleOnRight ? Gravity.START : Gravity.END;
            FrameLayout.LayoutParams tailParams = (FrameLayout.LayoutParams) tail.getLayoutParams();
            tailParams.gravity = Gravity.TOP | (bubbleOnRight ? Gravity.END : Gravity.START);
            tail.setLayoutParams(tailParams);
            tail.setScaleX(bubbleOnRight ? 1f : -1f);
            tail.setVisibility(View.VISIBLE);
        }
        card.setLayoutParams(cardParams);
        popover.setLayoutParams(popoverParams);
        placed = true;
    }

    /**
     * Vertical layout: the tail level with the bubble, the card kept on screen and clear of the
     * clipboard toast. When the card has to move up, the tail still points as close to the bubble as
     * its rounded corners allow.
     */
    private void placeVertically() {
        if (!placed || dismissing || card.getWidth() == 0) return;
        if (anchor != null) {
            int cardHeight = card.getHeight();
            int margin = dp(root.getContext(), MARGIN_DP);
            int tailHalf = dp(root.getContext(), TAIL_HEIGHT_DP) / 2;
            int lowest = root.getHeight() - dp(root.getContext(), TOAST_SPACE_DP) - cardHeight;
            int top = Math.max(margin, Math.min(anchor.centerY() - dp(root.getContext(), TAIL_CENTER_DP), lowest));
            int inset = dp(root.getContext(), 10) + tailHalf;
            int tailCenter = Math.max(inset, Math.min(anchor.centerY() - top, cardHeight - inset));
            popover.setTranslationY(top);
            tail.setTranslationY(tailCenter - tailHalf);
            popover.setPivotX(bubbleOnRight ? popover.getWidth() : 0f);
            popover.setPivotY(tailCenter);
        } else {
            popover.setPivotX(popover.getWidth() / 2f);
            popover.setPivotY(popover.getHeight() / 2f);
        }
        placedVertically = true;
        if (windowShown) enter();
    }

    private void enter() {
        if (shown || dismissing) return;
        shown = true;
        popover.setVisibility(View.VISIBLE);
        if (!ValueAnimator.areAnimatorsEnabled()) { scrim.setAlpha(1f); return; }
        popover.setAlpha(0f);
        popover.setScaleX(HIDDEN_SCALE);
        popover.setScaleY(HIDDEN_SCALE);
        scrim.animate().alpha(1f).setDuration(220).start();
        popover.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
}
