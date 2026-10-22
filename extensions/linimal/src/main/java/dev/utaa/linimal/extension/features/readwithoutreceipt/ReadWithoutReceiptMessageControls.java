package dev.utaa.linimal.extension.features.readwithoutreceipt;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

import dev.utaa.linimal.extension.settings.ui.LinimalPalette;

/** 公式の行操作を保ち、未読行の操作と実際の既読境界だけを追加します。 */
public final class ReadWithoutReceiptMessageControls {
    private static final int ACTION = 1;
    private static final int BOUNDARY_BEFORE = 2;
    private static final int BOUNDARY_AFTER = 4;
    // LINE 26.11.0 の受信メッセージで共通の時刻表示。
    private static final int TIMESTAMP_ID = 0x7f0b08a6; // chat_ui_row_timestamp
    private static final WeakHashMap<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();

    private ReadWithoutReceiptMessageControls() {
    }

    /** serverMessageId は共通表示 model の値。localMessageId への代替は行いません。 */
    public static void bind(View row, String chatId, long serverMessageId, boolean incoming,
            boolean firstUnread, String nativeReadPosition) {
        try {
            WeakReference<Binding> previous = BINDINGS.remove(row);
            Binding old = previous == null ? null : previous.get();
            if (old != null) {
                old.dispose();
            }
            if (row == null || !incoming || chatId == null || chatId.isEmpty() || serverMessageId <= 0L) {
                return;
            }
            Activity activity = activityFor(row.getContext());
            ReadWithoutReceiptHooks.Suppression session = ReadWithoutReceiptHooks.currentSuppression();
            if (activity == null || activity.isFinishing() || activity.isDestroyed() || session == null
                    || !chatId.equals(session.chatId) || !ReadWithoutReceiptHooks.shouldBlockMarkAsRead(chatId)
                    || (session.confirmed && !session.owns(activity))) {
                return;
            }
            // 26.11.0 chat_ui_row.xml: FrameLayout > 縦 LinearLayout > 公式の swipe/content。
            // 末尾への追加だけなので、balloon の寸法・click/long-click/swipe はそのままです。
            if (!(row instanceof FrameLayout) || ((FrameLayout) row).getChildCount() == 0
                    || !(((FrameLayout) row).getChildAt(0) instanceof LinearLayout)) {
                return;
            }
            LinearLayout container = (LinearLayout) ((FrameLayout) row).getChildAt(0);
            if (container.getOrientation() != LinearLayout.VERTICAL) {
                return;
            }
            Binding binding = new Binding(row, container, activity, chatId, serverMessageId, session.token,
                    firstUnread, nativeReadPosition);
            BINDINGS.put(row, new WeakReference<>(binding));
            row.addOnAttachStateChangeListener(binding);
            binding.observe();
            binding.refresh();
        } catch (Throwable ignored) {
            // 表示に失敗しても、公式の行 bind と既読抑制を継続します。
        }
    }

    private static Activity activityFor(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) {
                return null;
            }
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private static final class Binding implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> row;
        private final WeakReference<LinearLayout> container;
        private final WeakReference<Activity> owner;
        private final String chatId;
        private final long point;
        private final long token;
        private final boolean firstUnread;
        private final String nativeReadPosition;
        private boolean baselineObserved;
        private LinearLayout boundary;
        private int boundaryPlacement;
        private Button button;
        private FrameLayout actionRow;
        private WeakReference<TextView> timestamp;
        private final int[] location = new int[2];
        private ViewTreeObserver observer;
        private boolean disposed;

        Binding(View row, LinearLayout container, Activity owner, String chatId, long point, long token,
                boolean firstUnread, String nativeReadPosition) {
            this.row = new WeakReference<>(row);
            this.container = new WeakReference<>(container);
            this.owner = new WeakReference<>(owner);
            this.chatId = chatId;
            this.point = point;
            this.token = token;
            this.firstUnread = firstUnread;
            this.nativeReadPosition = nativeReadPosition;
        }

        private boolean current() {
            View view = row.get();
            Activity activity = owner.get();
            WeakReference<Binding> saved = view == null ? null : BINDINGS.get(view);
            ReadWithoutReceiptHooks.Suppression session = ReadWithoutReceiptHooks.currentSuppression();
            return !disposed && saved != null && saved.get() == this && activity != null
                    && !activity.isFinishing() && !activity.isDestroyed()
                    && session != null && session.token == token && chatId.equals(session.chatId)
                    && (!session.confirmed || session.owns(activity))
                    && ReadWithoutReceiptHooks.shouldBlockMarkAsRead(chatId);
        }

        void observe() {
            View view = row.get();
            if (view != null && view.isAttachedToWindow() && (observer == null || !observer.isAlive())) {
                observer = view.getViewTreeObserver();
                observer.addOnPreDrawListener(this);
            }
        }

        void refresh() {
            try {
                if (!current()) {
                    dispose();
                    return;
                }
                View view = row.get();
                Activity activity = owner.get();
                LinearLayout parent = container.get();
                if (view == null || parent == null || !view.isAttachedToWindow()
                        || ReadWithoutReceiptHooks.sessionTokenFor(activity) != token) {
                    removeButton();
                    removeBoundary();
                    return;
                }
                if (!baselineObserved) {
                    ReadWithoutReceiptManualHooks.observeReadPosition(activity, chatId, nativeReadPosition);
                    baselineObserved = true;
                }
                int presentation = ReadWithoutReceiptManualHooks.presentation(activity, point, firstUnread);
                if (presentation < 0) {
                    // 入室前の既読位置が不明な間、履歴を未読と決めつけません。
                    removeButton();
                    removeBoundary();
                    return;
                }
                if (presentation == 0) {
                    dispose();
                    return;
                }
                boolean showAction = (presentation & ACTION) != 0;
                if (!showAction) {
                    removeButton();
                }
                updateBoundary(parent, presentation & (BOUNDARY_BEFORE | BOUNDARY_AFTER));
                if (!showAction) {
                    return;
                }
                if (button == null) {
                    View nativeTime = view.findViewById(TIMESTAMP_ID);
                    if (!(nativeTime instanceof TextView) || !nativeTime.isShown()) {
                        return;
                    }
                    timestamp = new WeakReference<>((TextView) nativeTime);
                    FrameLayout action = new FrameLayout(activity);
                    actionRow = action;
                    Button control = new Button(activity);
                    button = control;
                    LinimalPalette palette = LinimalPalette.of(activity);
                    control.setText("既読にする");
                    control.setContentDescription("このメッセージまで既読にする");
                    control.setTextSize(11);
                    control.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
                    control.setPaintFlags(control.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
                    GradientDrawable rippleMask = new GradientDrawable();
                    rippleMask.setColor(palette.primaryText);
                    rippleMask.setCornerRadius(dp(view, 4));
                    control.setBackground(new RippleDrawable(
                            ColorStateList.valueOf(palette.divider), null, rippleMask));
                    control.setStateListAnimator(null);
                    control.setElevation(0f);
                    control.setGravity(Gravity.TOP | Gravity.LEFT);
                    control.setPadding(0, 0, 0, 0);
                    int minimum = Math.round(48 * view.getResources().getDisplayMetrics().density);
                    control.setMinHeight(minimum);
                    control.setMinimumHeight(minimum);
                    control.setMinWidth(minimum);
                    control.setMinimumWidth(minimum);
                    control.setAllCaps(false);
                    control.setVisibility(View.INVISIBLE);
                    control.setOnClickListener(clicked -> {
                        try {
                            // bind ごとの token・server ID を固定し、リサイクル済み listener を拒否します。
                            if (clicked == button && current()
                                    && ReadWithoutReceiptHooks.sessionTokenFor(owner.get()) == token
                                    && ReadWithoutReceiptManualHooks.isReady(owner.get())
                                    && !ReadWithoutReceiptManualHooks.isRead(owner.get(), point)) {
                                ReadWithoutReceiptManualHooks.markMessage(owner.get(), point);
                                refresh();
                            }
                        } catch (Throwable ignored) {
                            dispose();
                        }
                    });
                    action.addView(control, new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP | Gravity.LEFT));
                    parent.addView(action, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
                }
                boolean ready = ReadWithoutReceiptManualHooks.isReady(activity);
                button.setEnabled(ready);
                button.setAlpha(ready ? 1f : 0.45f);
                positionAction();
            } catch (Throwable ignored) {
                dispose();
            }
        }

        private void positionAction() {
            TextView time = timestamp == null ? null : timestamp.get();
            FrameLayout action = actionRow;
            Button control = button;
            if (time == null || action == null || control == null) {
                return;
            }
            if (!time.isShown()) {
                action.setVisibility(View.GONE);
                return;
            }
            action.setVisibility(View.VISIBLE);
            if (control.getTextColors() != time.getTextColors()) {
                // LINE の時刻表示の色を使い、壁紙や独自テーマでも文字リンクだけを馴染ませます。
                control.setTextColor(time.getTextColors());
                control.setShadowLayer(time.getShadowRadius(), time.getShadowDx(),
                        time.getShadowDy(), time.getShadowColor());
            }
            if (action.isLayoutRequested() || control.getWidth() == 0) {
                control.setVisibility(View.INVISIBLE);
                return;
            }
            time.getLocationOnScreen(location);
            int timeX = location[0];
            action.getLocationOnScreen(location);
            int left = Math.max(0, Math.min(timeX - location[0], action.getWidth() - control.getWidth()));
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) control.getLayoutParams();
            if (layout.leftMargin != left) {
                layout.leftMargin = left;
                control.setLayoutParams(layout);
            }
            control.setVisibility(View.VISIBLE);
        }

        private void updateBoundary(LinearLayout parent, int placement) {
            if (boundaryPlacement == placement) {
                return;
            }
            removeBoundary();
            if (placement == 0) {
                return;
            }
            View view = row.get();
            Activity activity = owner.get();
            if (view == null || activity == null) {
                return;
            }
            LinimalPalette palette = LinimalPalette.of(activity);
            LinearLayout separator = new LinearLayout(activity);
            boundary = separator;
            boundaryPlacement = placement;
            separator.setOrientation(LinearLayout.HORIZONTAL);
            separator.setGravity(Gravity.CENTER_VERTICAL);
            separator.setPadding(0, dp(view, 10), 0, dp(view, 10));

            View leading = new View(activity);
            leading.setBackgroundColor(palette.secondaryText);
            separator.addView(leading, new LinearLayout.LayoutParams(0, dp(view, 1), 1f));
            TextView label = new TextView(activity);
            label.setText("ここまで既読");
            label.setTextSize(12);
            label.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
            label.setTextColor(palette.primaryText);
            label.setPadding(dp(view, 8), dp(view, 2), dp(view, 8), dp(view, 2));
            label.setBackgroundColor(palette.background);
            label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            separator.addView(label, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            View trailing = new View(activity);
            trailing.setBackgroundColor(palette.secondaryText);
            separator.addView(trailing, new LinearLayout.LayoutParams(0, dp(view, 1), 1f));
            parent.addView(separator, placement == BOUNDARY_BEFORE ? 0 : parent.getChildCount(),
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        private void removeBoundary() {
            LinearLayout previous = boundary;
            boundary = null;
            boundaryPlacement = 0;
            if (previous != null && previous.getParent() instanceof LinearLayout) {
                ((LinearLayout) previous.getParent()).removeView(previous);
            }
        }

        private static int dp(View view, int value) {
            return Math.round(value * view.getResources().getDisplayMetrics().density);
        }

        private void removeButton() {
            Button old = button;
            FrameLayout previous = actionRow;
            button = null;
            actionRow = null;
            timestamp = null;
            if (old != null) {
                old.setOnClickListener(null);
            }
            if (previous != null && previous.getParent() instanceof LinearLayout) {
                ((LinearLayout) previous.getParent()).removeView(previous);
            }
        }

        private void stopObserving() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
            }
            observer = null;
        }

        void dispose() {
            disposed = true;
            try {
                stopObserving();
                removeButton();
                removeBoundary();
                View view = row.get();
                if (view != null) {
                    view.removeOnAttachStateChangeListener(this);
                    WeakReference<Binding> saved = BINDINGS.get(view);
                    if (saved != null && saved.get() == this) {
                        BINDINGS.remove(view);
                    }
                }
            } catch (Throwable ignored) {
                // Window の破棄中でも公式処理へ例外を返しません。
            }
        }

        @Override
        public boolean onPreDraw() {
            refresh();
            return true;
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            try {
                observe();
                refresh();
            } catch (Throwable ignored) {
                dispose();
            }
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            try {
                stopObserving();
                removeButton();
                removeBoundary();
            } catch (Throwable ignored) {
                dispose();
            }
        }
    }
}
