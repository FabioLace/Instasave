package app.instasave;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import static app.instasave.UiMotion.dp;

/** Keeps a draggable bubble above other apps; tapping it opens the quick download sheet. */
public final class BubbleService extends Service {
    private static final String ACTION_STOP = "app.instasave.action.STOP_BUBBLE";
    private static final String CHANNEL_ID = "bubble";
    private static final int NOTIFICATION_ID = 1;
    // True from the start request until the service is destroyed, so the in-app switch does not
    // flicker while the service is still being created.
    private static volatile boolean enabled;
    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    // The window is larger than the visible button so the button's shadow is not clipped.
    private FrameLayout bubble;
    private ImageView button;
    private ImageView dismissTarget;
    /** The bubble is shrinking away before the service stops; a new start request brings it back. */
    private boolean hiding;

    static boolean isEnabled() { return enabled; }

    static void start(Context context) {
        enabled = true;
        context.startForegroundService(new Intent(context, BubbleService.class));
    }

    /** Asks the service to shrink the bubble away and stop; nothing to do when it isn't running. */
    static void stop(Context context) {
        if (!enabled) return;
        try {
            context.startService(new Intent(context, BubbleService.class).setAction(ACTION_STOP));
        } catch (Exception error) {
            // Background start limits: stop it directly, without the animation.
            context.stopService(new Intent(context, BubbleService.class));
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        enabled = true;
        startInForeground();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        showBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { hideBubble(); return START_NOT_STICKY; }
        // Every startForegroundService() call must be followed by startForeground(), also when the
        // service is already running, or Android stops the app.
        startInForeground();
        if (hiding) {
            // Switched back on while shrinking: cancelling the animation also skips its stopSelf().
            hiding = false;
            enabled = true;
            showDismissTarget(false);
            popIn();
        }
        return START_STICKY;
    }

    private void startInForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, getString(R.string.bubble_title),
                NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, BubbleService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(getString(R.string.bubble_notification_title))
                .setContentText(getString(R.string.bubble_notification_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_clear),
                        getString(R.string.bubble_notification_stop), stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // DragListener calls performClick() itself for taps, so the bubble stays reachable by TalkBack.
    @SuppressLint("ClickableViewAccessibility")
    // STYLE: floating bubble: 56dp circle, drawable/bg_bubble with drawable/ic_bubble_download.
    private void showBubble() {
        windowManager = getSystemService(WindowManager.class);
        int shadowSpace = dp(this, 8);
        int size = dp(this, 56) + shadowSpace * 2;
        button = new ImageView(this);
        button.setImageResource(R.drawable.ic_bubble_download);
        button.setBackgroundResource(R.drawable.bg_bubble);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setElevation(dp(this, 5));
        button.setContentDescription(getString(R.string.bubble_description));
        button.setOnClickListener(v -> openQuickDownload());
        button.setOnTouchListener(new DragListener());
        bubble = new FrameLayout(this);
        bubble.setPadding(shadowSpace, shadowSpace, shadowSpace, shadowSpace);
        bubble.setClipToPadding(false);
        bubble.addView(button, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        params.x = metrics.widthPixels - size;
        params.y = metrics.heightPixels / 3;
        // Added first so it stays below the bubble, which can then be dropped onto it.
        addDismissTarget();
        windowManager.addView(bubble, params);
        popIn();
    }

    // STYLE: bubble entrance: pops out from nothing with a slight overshoot, 280ms.
    private void popIn() {
        button.animate().cancel();
        button.setScaleX(1f);
        button.setScaleY(1f);
        button.setAlpha(1f);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        button.setScaleX(0f);
        button.setScaleY(0f);
        button.setAlpha(0f);
        button.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(0).setDuration(280)
                .setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    /**
     * Shrinks the bubble away, together with the drop target when it was dropped there, then stops
     * the service. Every way of hiding the bubble goes through here: the drop target, the in-app
     * switch and the notification action.
     */
    // STYLE: bubble exit: shrinks and fades out, 200ms.
    private void hideBubble() {
        if (hiding) return;
        enabled = false;
        if (bubble == null || !bubble.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { stopSelf(); return; }
        hiding = true;
        button.animate().cancel();
        button.animate().scaleX(0f).scaleY(0f).alpha(0f).setStartDelay(0).setDuration(200)
                .setInterpolator(new AccelerateInterpolator()).withEndAction(this::stopSelf).start();
        if (dismissTarget.getVisibility() == View.VISIBLE) {
            dismissTarget.animate().cancel();
            dismissTarget.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(200).start();
        }
    }

    // STYLE: drop target to close the bubble: 64dp, drawable/bg_bubble_dismiss.
    private void addDismissTarget() {
        int size = dp(this, 64);
        dismissTarget = new ImageView(this);
        dismissTarget.setImageResource(R.drawable.ic_clear);
        dismissTarget.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        dismissTarget.setBackgroundResource(R.drawable.bg_bubble_dismiss);
        dismissTarget.setScaleType(ImageView.ScaleType.CENTER);
        dismissTarget.setVisibility(View.GONE);
        WindowManager.LayoutParams target = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        target.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        target.y = dp(this, 48);
        windowManager.addView(dismissTarget, target);
    }

    private void showDismissTarget(boolean visible) {
        dismissTarget.animate().cancel();
        dismissTarget.setScaleX(1f);
        dismissTarget.setScaleY(1f);
        dismissTarget.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) UiMotion.fadeIn(dismissTarget);
    }

    /** Whether a finger at these screen coordinates is close enough to the target to drop the bubble. */
    private boolean isOverDismissTarget(float rawX, float rawY) {
        if (dismissTarget.getVisibility() != View.VISIBLE) return false;
        int[] location = new int[2];
        dismissTarget.getLocationOnScreen(location);
        float centerX = location[0] + dismissTarget.getWidth() / 2f;
        float centerY = location[1] + dismissTarget.getHeight() / 2f;
        return Math.hypot(rawX - centerX, rawY - centerY) < dp(this, 72);
    }

    private void openQuickDownload() {
        UiMotion.tap(button);
        // The button's place on screen, so the sheet can open its speech bubble next to it. Read from
        // the window and the button's layout position, which the tap's scale animation doesn't change.
        int[] window = new int[2];
        bubble.getLocationOnScreen(window);
        Rect bounds = new Rect(window[0] + button.getLeft(), window[1] + button.getTop(),
                window[0] + button.getRight(), window[1] + button.getBottom());
        // No system transition: a new task otherwise slides in, hiding the sheet's own animation.
        Intent sheet = new Intent(this, QuickDownloadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        sheet.setSourceBounds(bounds);
        startActivity(sheet);
    }

    /** Moves the bubble to the nearest side, keeping it fully on screen; the shadow space acts as margin. */
    private void snapToEdge() {
        if (bubble == null || !bubble.isAttachedToWindow()) return;
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int size = params.width;
        int targetX = params.x + size / 2 < metrics.widthPixels / 2 ? 0 : metrics.widthPixels - size;
        params.y = Math.max(0, Math.min(params.y, metrics.heightPixels - size));
        ValueAnimator animator = ValueAnimator.ofInt(params.x, targetX).setDuration(180);
        animator.addUpdateListener(animation -> {
            if (!bubble.isAttachedToWindow()) return;
            params.x = (int) animation.getAnimatedValue();
            windowManager.updateViewLayout(bubble, params);
        });
        animator.start();
    }

    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        snapToEdge();
    }

    @Override public void onDestroy() {
        enabled = false;
        if (bubble != null && bubble.isAttachedToWindow()) windowManager.removeView(bubble);
        if (dismissTarget != null && dismissTarget.isAttachedToWindow()) windowManager.removeView(dismissTarget);
        super.onDestroy();
    }

    /**
     * Drags the bubble; a touch that never exceeds the touch slop is delivered as a click. Dropping
     * the bubble on the target at the bottom hides it, like closing the service from the app.
     */
    private final class DragListener implements View.OnTouchListener {
        private final int touchSlop = ViewConfiguration.get(BubbleService.this).getScaledTouchSlop();
        private float downX;
        private float downY;
        private int startX;
        private int startY;
        private boolean dragging;
        private boolean overTarget;

        @Override public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    startX = params.x;
                    startY = params.y;
                    dragging = false;
                    overTarget = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downX;
                    float dy = event.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) < touchSlop) return true;
                    if (!dragging) showDismissTarget(true);
                    dragging = true;
                    params.x = startX + Math.round(dx);
                    params.y = startY + Math.round(dy);
                    windowManager.updateViewLayout(bubble, params);
                    boolean over = isOverDismissTarget(event.getRawX(), event.getRawY());
                    if (over != overTarget) {
                        overTarget = over;
                        dismissTarget.animate().scaleX(over ? 1.25f : 1f).scaleY(over ? 1.25f : 1f).setDuration(120).start();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) { view.performClick(); return true; }
                    if (overTarget) { hideBubble(); return true; }
                    showDismissTarget(false);
                    snapToEdge();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (dragging) { showDismissTarget(false); snapToEdge(); }
                    return true;
                default:
                    return false;
            }
        }
    }
}
