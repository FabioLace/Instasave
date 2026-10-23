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
import android.widget.FrameLayout;
import android.widget.ImageView;

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

    static boolean isEnabled() { return enabled; }

    static void start(Context context) {
        enabled = true;
        context.startForegroundService(new Intent(context, BubbleService.class));
    }

    static void stop(Context context) { context.stopService(new Intent(context, BubbleService.class)); }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        enabled = true;
        startInForeground();
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        showBubble();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
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
    private void showBubble() {
        windowManager = getSystemService(WindowManager.class);
        int shadowSpace = dp(8);
        int size = dp(56) + shadowSpace * 2;
        button = new ImageView(this);
        button.setImageResource(R.drawable.ic_bubble_download);
        button.setBackgroundResource(R.drawable.bg_bubble);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setElevation(dp(5));
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
    }

    private void addDismissTarget() {
        int size = dp(64);
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
        target.y = dp(48);
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
        return Math.hypot(rawX - centerX, rawY - centerY) < dp(72);
    }

    private void openQuickDownload() {
        UiMotion.tap(button);
        startActivity(new Intent(this, QuickDownloadActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
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

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

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
                    showDismissTarget(false);
                    if (overTarget) stopSelf();
                    else snapToEdge();
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
