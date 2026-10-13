package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import dev.utaa.linimal.extension.config.LinimalConfig;
import dev.utaa.linimal.extension.settings.ui.LinimalPalette;

/** 確定済み閲覧セッションの既読が無効な間、操作を遮らない案内を重ねます。 */
final class ReadWithoutReceiptNotice {
    private static final String MESSAGE = "現在、既読は無効です。メッセージを送信すると既読になります";
    private static final String KEEP_UNREAD_MESSAGE = "現在、既読は無効です。送信しても既読はつきません";

    private long pendingToken;
    private WeakReference<Activity> owner;
    private WeakReference<FrameLayout> decor;
    private TextView notice;
    private String chatId;
    private final Runnable show = this::showPending;
    private final ViewTreeObserver.OnPreDrawListener checkSession = () -> {
        try {
            Activity activity = owner == null ? null : owner.get();
            if (!isCurrent(activity, pendingToken)) {
                dismiss();
            } else if (notice != null) {
                String message = currentMessage();
                if (!message.contentEquals(notice.getText())) {
                    notice.setText(message);
                }
            }
        } catch (Throwable ignored) {
            dismiss();
        }
        return true;
    };

    void onResumed(Activity activity) {
        try {
            dismiss();
            if (activity == null) {
                return;
            }
            long token = ReadWithoutReceiptHooks.sessionTokenFor(activity);
            if (token == 0L) {
                return;
            }
            View root = activity.getWindow().getDecorView();
            if (!(root instanceof FrameLayout)) {
                return;
            }
            owner = new WeakReference<>(activity);
            decor = new WeakReference<>((FrameLayout) root);
            pendingToken = token;
            // resume の処理が終わり、LINE の画面が用意されてから所有者を再検証します。
            if (!root.post(show)) {
                dismiss();
            }
        } catch (Throwable ignored) {
            dismiss();
        }
    }

    void onHidden(Activity activity) {
        if (owner != null && owner.get() == activity) {
            dismiss();
        }
    }

    private boolean isCurrent(Activity activity, long token) {
        return activity != null && token != 0L
                && ReadWithoutReceiptHooks.sessionTokenFor(activity) == token
                && chatId != null && ReadWithoutReceiptHooks.shouldBlockMarkAsRead(chatId);
    }

    private void showPending() {
        try {
            Activity activity = owner == null ? null : owner.get();
            FrameLayout root = decor == null ? null : decor.get();
            if (activity == null || root == null || !root.isAttachedToWindow()
                    || activity.isFinishing() || activity.isDestroyed()) {
                dismiss();
                return;
            }
            Bundle extras = activity.getIntent().getExtras();
            Object request = extras == null ? null : extras.getParcelable("chatHistoryRequest");
            chatId = ReadWithoutReceiptHooks.chatIdFromRequest(request);
            if (!isCurrent(activity, pendingToken)) {
                dismiss();
                return;
            }
            LinimalPalette palette = LinimalPalette.of(activity);
            TextView text = new TextView(activity);
            notice = text;
            text.setText(currentMessage());
            text.setTextSize(14);
            text.setTextColor(palette.primaryText);
            text.setGravity(Gravity.START);
            text.setPadding(dp(root, 16), dp(root, 12), dp(root, 16), dp(root, 12));
            text.setLineSpacing(dp(root, 3), 1f);
            text.setClickable(false);
            text.setFocusable(false);
            text.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            GradientDrawable background = new GradientDrawable();
            background.setColor(palette.background);
            background.setCornerRadius(dp(root, 10));
            background.setStroke(dp(root, 1), palette.divider);
            text.setBackground(background);
            text.setElevation(dp(root, 6));

            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP);
            int margin = dp(root, 16);
            int left = 0;
            int top = 0;
            int right = 0;
            WindowInsets insets = root.getRootWindowInsets();
            if (insets != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                            | WindowInsets.Type.displayCutout());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                        left = Math.max(left, insets.getDisplayCutout().getSafeInsetLeft());
                        top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
                        right = Math.max(right, insets.getDisplayCutout().getSafeInsetRight());
                    }
                }
            }
            layout.setMargins(left + margin, top + dp(root, 12), right + margin, 0);
            root.addView(text, layout);
            root.getViewTreeObserver().addOnPreDrawListener(checkSession);
            // 送信などで token が消えた場合は、次の描画より前に案内も取り除きます。
            if (!isCurrent(activity, pendingToken)) {
                dismiss();
                return;
            }
        } catch (Throwable ignored) {
            // 案内の失敗では抑制状態を変更せず、公式画面の操作を続けます。
            dismiss();
        }
    }

    private void dismiss() {
        FrameLayout root = decor == null ? null : decor.get();
        TextView previous = notice;
        owner = null;
        decor = null;
        notice = null;
        chatId = null;
        pendingToken = 0L;
        if (root == null) {
            return;
        }
        try {
            root.removeCallbacks(show);
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(checkSession);
            }
            if (previous != null) {
                root.removeView(previous);
            }
        } catch (Throwable ignored) {
            // 破棄中の Window から案内を外せなくても、Activity の参照は残しません。
        }
    }

    private static String currentMessage() {
        return LinimalConfig.get().isKeepUnreadAfterSendEnabled() ? KEEP_UNREAD_MESSAGE : MESSAGE;
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
