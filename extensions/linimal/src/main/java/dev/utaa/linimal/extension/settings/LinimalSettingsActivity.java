package dev.utaa.linimal.extension.settings;

import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Insets;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.Window;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.utaa.linimal.extension.config.LinimalConfig;
import dev.utaa.linimal.extension.config.LinimalConfigBootstrap;
import dev.utaa.linimal.extension.config.LinimalConfigHealth;
import dev.utaa.linimal.extension.config.ReadReceiptMode;
import dev.utaa.linimal.extension.settings.ui.BackArrowDrawable;
import dev.utaa.linimal.extension.settings.ui.ChevronDrawable;
import dev.utaa.linimal.extension.settings.ui.LinimalPalette;
import dev.utaa.linimal.extension.status.PatchStatus;
import dev.utaa.linimal.extension.status.PatchStatusPresenter;
import dev.utaa.linimal.extension.status.PatchStatusReadResult;
import dev.utaa.linimal.extension.status.PatchStatusReport;

/** LINE の設定画面に合わせた見た目で、Linimal の設定と適用状況を表示します。 */
public final class LinimalSettingsActivity extends Activity {
    private static final String STATE_PAGE_PATH = "linimal.settings.page_path";

    /** 描画済みの Switch だけを持ち、画面再構築時に必ず破棄します。 */
    private final List<FeatureRow> featureRows = new ArrayList<>();
    /** この Activity 内のページ状態はこの navigation だけで管理します。 */
    private final SettingsNavigation navigation = new SettingsNavigation();

    private LinimalPalette palette;
    /** API 33 以上で登録した OnBackInvokedCallback。API 32 では null のままです。 */
    private Object systemBackCallback;
    private PatchStatusReadResult patchStatusResult;
    private PatchStatus readReceiptPatchStatus;
    private TextView readReceiptSummary;
    private Switch readReceiptToggle;

    private static final class FeatureRow {
        private final FeatureCatalog.Entry entry;
        private final PatchStatus patchStatus;
        private final TextView summary;
        private final Switch toggle;

        FeatureRow(
                FeatureCatalog.Entry entry,
                PatchStatus patchStatus,
                TextView summary,
                Switch toggle) {
            this.entry = entry;
            this.patchStatus = patchStatus;
            this.summary = summary;
            this.toggle = toggle;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinimalConfigBootstrap.initialize(this);
        palette = LinimalPalette.of(this);
        hideSystemActionBar();
        applySystemBars();
        registerSystemBackCallback();

        // patch status は LinimalConfig が初期化時に読んだ結果を共有し、hook の判定と表示を一致させます。
        patchStatusResult = LinimalConfig.get().getPatchStatusResult();
        if (savedInstanceState != null) {
            navigation.restore(savedInstanceState.getStringArray(STATE_PAGE_PATH));
        }
        renderCurrentPage();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putStringArray(STATE_PAGE_PATH, navigation.serialize());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        unregisterSystemBackCallback();
        super.onDestroy();
    }

    /**
     * predictive back が無効な経路（API 32、または OnBackInvokedCallback 未使用時）のバックです。
     * API 33 以上で {@link #systemBackCallback} が登録されている場合、こちらは呼ばれません。
     */
    @Override
    public void onBackPressed() {
        navigateBack();
    }

    /**
     * LINE 本体は targetSdk 36 のため、API 33 以上では onBackPressed() ではなく
     * OnBackInvokedDispatcher へバックが配送されます。super.onCreate() が登録する既定の
     * コールバックより後に登録することで、同じ優先度の中でこちらが先に呼ばれます。
     */
    private void registerSystemBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        systemBackCallback = SystemBack.register(this);
    }

    private void unregisterSystemBackCallback() {
        if (systemBackCallback == null) {
            return;
        }
        SystemBack.unregister(this, systemBackCallback);
        systemBackCallback = null;
    }

    /** API 33 以上でだけ読み込まれるよう、predictive back の API をこのクラスに閉じ込めます。 */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private static final class SystemBack {
        private SystemBack() {
        }

        static Object register(LinimalSettingsActivity activity) {
            OnBackInvokedCallback callback = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    activity.navigateBack();
                }
            };
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            return callback;
        }

        static void unregister(LinimalSettingsActivity activity, Object callback) {
            activity.getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback((OnBackInvokedCallback) callback);
        }
    }

    private void navigateBack() {
        if (navigation.pop() == SettingsNavigation.PopResult.FINISH_ACTIVITY) {
            finish();
            return;
        }
        renderCurrentPage();
    }

    private void openPage(SettingsPage page) {
        // 同じページの重複 push や子ページからの深い push は navigation が拒否します。
        if (navigation.push(page)) {
            renderCurrentPage();
        }
    }

    /** ページ遷移時は View を作り直し、古い View への参照を残しません。 */
    private void renderCurrentPage() {
        clearRenderedReferences();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);
        applyWindowInsets(root);
        root.addView(createHeader(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(24));

        SettingsPage page = navigation.getCurrentPage();
        if (page == SettingsPage.ROOT) {
            addRootPage(content);
        } else if (page == SettingsPage.PATCH_STATUS) {
            addPatchStatusPage(content);
        } else {
            addFeaturePage(content, page);
        }

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        // Config health は画面を描画するたびに取得し、書き込み失敗後も状態を更新します。
        renderRuntimeConfig();
    }

    private void clearRenderedReferences() {
        featureRows.clear();
        readReceiptPatchStatus = null;
        readReceiptSummary = null;
        readReceiptToggle = null;
    }

    private void addRootPage(LinearLayout parent) {
        addCategoryRow(parent, SettingsPage.ADS,
                "広告", "広告の表示を止めます。");
        addCategoryRow(parent, SettingsPage.AGENT_I,
                "Agent i・LINE AI", "Agent i と LINE AI の入口を場所ごとに設定します。");
        addCategoryRow(parent, SettingsPage.HIDE,
                "表示を消す", "画面ごとに表示する項目を選びます。");
        addCategoryRow(parent, SettingsPage.READ_RECEIPT,
                "既読", "既読の送信と、既読をつけずに読む機能を設定します。");
        addCategoryRow(parent, SettingsPage.GENERAL,
                "一般", "Premium の案内とリンクの開き方を設定します。");
        addCategoryRow(parent, SettingsPage.PATCH_STATUS,
                "Patch Status", "パッチの適用状況を確認します。");
    }

    private void addFeaturePage(LinearLayout parent, SettingsPage page) {
        if (patchStatusResult == null || !patchStatusResult.isAvailable()) {
            // status がないときは誤操作を防ぐため、Switch 自体を表示しません。
            addUnavailableFeatureMessage(parent, "パッチ情報を読み取れないため、機能の設定を変更できません。");
            return;
        }

        PatchStatusReport report = patchStatusResult.getReport();
        List<FeatureCatalog.Group> groups = FeatureCatalog.installedGroupsForPage(
                page, report.getFeatureIds());
        boolean hasEntry = false;
        for (FeatureCatalog.Group group : groups) {
            // 表示できる項目が残った小見出しだけ Group になるため、見出しだけが残ることはありません。
            if (group.getSection() != null) {
                addSectionHeader(parent, group.getSection().getTitle());
            }
            for (FeatureCatalog.Entry entry : group.getEntries()) {
                addFeatureRow(parent, entry, report.getFeatureStatus(entry.getFeatureId()));
                hasEntry = true;
            }
        }

        if (page == SettingsPage.READ_RECEIPT) {
            hasEntry |= addReadReceiptRow(parent, report);
        }

        if (!hasEntry) {
            addUnavailableFeatureMessage(parent, "このページで利用できる機能はありません。");
        }
    }

    /** status presenter の行をそのまま用い、report 内の全 record を表示します。 */
    private void addPatchStatusPage(LinearLayout parent) {
        List<PatchStatusPresenter.Row> rows = PatchStatusPresenter.rows(patchStatusResult);
        for (PatchStatusPresenter.Row row : rows) {
            addStatusRow(parent, row);
        }
    }

    private void addCategoryRow(
            LinearLayout parent, SettingsPage page, String titleText, String summaryText) {
        LinearLayout row = createInteractiveRow(parent);
        row.setContentDescription(titleText + "。" + summaryText);
        row.setOnClickListener(view -> openPage(page));

        LinearLayout labels = createLabels(titleText, summaryText);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(labels, labelParams);

        ImageView chevron = new ImageView(this);
        chevron.setImageDrawable(new ChevronDrawable(palette.secondaryText, density()));
        chevron.setScaleType(ImageView.ScaleType.CENTER);
        chevron.setContentDescription("詳細を開く");
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout.LayoutParams chevronParams = new LinearLayout.LayoutParams(dp(32), dp(48));
        chevronParams.gravity = Gravity.CENTER_VERTICAL;
        chevronParams.leftMargin = dp(8);
        row.addView(chevron, chevronParams);
    }

    private boolean addReadReceiptRow(LinearLayout parent, PatchStatusReport report) {
        readReceiptPatchStatus = report.getFeatureStatus(ReadReceiptMode.FEATURE_ID);
        if (readReceiptPatchStatus == null) {
            return false;
        }

        LinearLayout row = createInteractiveRow(parent);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);

        TextView title = createText("通常チャットの自動既読を停止", 16, palette.primaryText);
        labels.addView(title, wrap());

        readReceiptSummary = createText("", 13, palette.secondaryText);
        readReceiptSummary.setPadding(0, dp(2), 0, 0);
        labels.addView(readReceiptSummary, wrap());

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(labels, labelParams);

        readReceiptToggle = createSwitch("通常チャットの自動既読を停止");
        addToggle(row, readReceiptToggle);
        row.setOnClickListener(view -> {
            if (readReceiptToggle.isEnabled()) {
                readReceiptToggle.toggle();
            }
        });
        return true;
    }

    /** 行と区別できるよう、小見出しは小さめの文字と控えめな色で上に余白を取って描画します。 */
    private void addSectionHeader(LinearLayout parent, String titleText) {
        TextView header = createText(titleText, 13, palette.secondaryText);
        header.setPadding(dp(20), dp(20), dp(20), dp(4));
        parent.addView(header, matchWidth());
    }

    private void addFeatureRow(LinearLayout parent, FeatureCatalog.Entry entry, PatchStatus patchStatus) {
        LinearLayout row = createInteractiveRow(parent);
        LinearLayout labels = createLabels(entry.getTitle(), "");
        TextView summary = (TextView) labels.getChildAt(1);

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.gravity = Gravity.CENTER_VERTICAL;
        row.addView(labels, labelParams);

        Switch toggle = createSwitch(entry.getTitle());
        addToggle(row, toggle);

        FeatureRow featureRow = new FeatureRow(entry, patchStatus, summary, toggle);
        featureRows.add(featureRow);
        row.setOnClickListener(view -> {
            if (toggle.isEnabled()) {
                toggle.toggle();
            }
        });
    }

    private void addStatusRow(LinearLayout parent, PatchStatusPresenter.Row row) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        container.setMinimumHeight(dp(56));
        container.setPadding(dp(20), dp(8), dp(20), dp(8));
        parent.addView(container, matchWidth());

        TextView title = createText(row.getTitle(), 16, palette.primaryText);
        container.addView(title, wrap());

        TextView detail = createText(row.getDetail(), 13, toneColor(row.getTone()));
        detail.setPadding(0, dp(2), 0, 0);
        container.addView(detail, wrap());
    }

    private LinearLayout createLabels(String titleText, String summaryText) {
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(createText(titleText, 16, palette.primaryText), wrap());

        TextView summary = createText(summaryText, 13, palette.secondaryText);
        summary.setPadding(0, dp(2), 0, 0);
        labels.addView(summary, wrap());
        return labels;
    }

    private TextView createText(String text, int textSizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout createInteractiveRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(dp(20), dp(8), dp(12), dp(8));
        row.setBackground(rowRipple());
        row.setClickable(true);
        parent.addView(row, matchWidth());
        return row;
    }

    private void addToggle(LinearLayout row, Switch toggle) {
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.gravity = Gravity.CENTER_VERTICAL;
        switchParams.leftMargin = dp(12);
        row.addView(toggle, switchParams);
    }

    private Switch createSwitch(String contentDescription) {
        Switch toggle = new Switch(this);
        toggle.setContentDescription(contentDescription);
        return toggle;
    }

    private void addUnavailableFeatureMessage(LinearLayout parent, String text) {
        TextView message = createText(text, 13, palette.statusError);
        message.setPadding(dp(20), dp(16), dp(20), dp(12));
        parent.addView(message, matchWidth());
    }

    private void renderRuntimeConfig() {
        LinimalConfig config = LinimalConfig.get();
        LinimalConfigHealth health = config.getRuntimeHealth();
        for (FeatureRow row : featureRows) {
            boolean available = row.patchStatus == PatchStatus.OK && health == LinimalConfigHealth.OK;
            row.summary.setText(available ? row.entry.getSummary()
                    : unavailableSummary(row.patchStatus, health));
            row.summary.setTextColor(available ? palette.secondaryText : palette.statusError);

            // 再描画で意図しない保存が起きないよう、listener を外してから状態を反映します。
            row.toggle.setOnCheckedChangeListener(null);
            row.toggle.setEnabled(available);
            row.toggle.setChecked(available && config.isSuppressionEnabled(row.entry.getFeature()));
            applySwitchColors(row.toggle, available);
            row.toggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton button, boolean checked) {
                    LinimalConfig.get().setSuppressionEnabled(row.entry.getFeature(), checked);
                    // 保存に失敗すると fail-open に戻るため、状態を読み直して表示します。
                    renderRuntimeConfig();
                }
            });
        }
        renderReadReceiptConfig(config, health);
    }

    private void renderReadReceiptConfig(LinimalConfig config, LinimalConfigHealth health) {
        if (readReceiptToggle == null || readReceiptSummary == null) {
            return;
        }

        boolean available = readReceiptPatchStatus == PatchStatus.OK
                && health == LinimalConfigHealth.OK;
        readReceiptSummary.setText(available
                ? "1対1・グループ・ルームが対象です。LINE の手動既読操作がある場合、その操作だけ送信します。OpenChat、Service Chat、AI Character は対象外です。"
                : unavailableSummary(readReceiptPatchStatus, health));
        readReceiptSummary.setTextColor(available ? palette.secondaryText : palette.statusError);

        readReceiptToggle.setOnCheckedChangeListener(null);
        readReceiptToggle.setEnabled(available);
        readReceiptToggle.setChecked(available && config.getReadReceiptMode() == ReadReceiptMode.MANUAL);
        applySwitchColors(readReceiptToggle, available);
        readReceiptToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                LinimalConfig.get().setReadReceiptMode(
                        checked ? ReadReceiptMode.MANUAL : ReadReceiptMode.NORMAL);
                renderRuntimeConfig();
            }
        });
    }

    private String unavailableSummary(PatchStatus patchStatus, LinimalConfigHealth health) {
        if (health != LinimalConfigHealth.OK) {
            return "設定を利用できないため、この機能は現在利用できません。";
        }
        if (patchStatus == null) {
            return "パッチの適用状況を確認できないため、この機能は現在利用できません。";
        }
        return "パッチが完全に適用されていないため、この機能は現在利用できません。";
    }

    private View createHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setBackgroundColor(palette.background);

        ImageView back = new ImageView(this);
        back.setImageDrawable(new BackArrowDrawable(palette.primaryText, density()));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setBackground(borderlessRipple());
        back.setContentDescription("戻る");
        back.setOnClickListener(view -> navigateBack());
        header.addView(back, new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.START | Gravity.CENTER_VERTICAL));

        TextView title = createText(titleFor(navigation.getCurrentPage()), 19, palette.primaryText);
        title.setSingleLine(true);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        titleParams.leftMargin = dp(52);
        header.addView(title, titleParams);

        return header;
    }

    private String titleFor(SettingsPage page) {
        switch (page) {
            case ADS:
                return "広告";
            case AGENT_I:
                return "Agent i・LINE AI";
            case HIDE:
                return "表示を消す";
            case READ_RECEIPT:
                return "既読";
            case GENERAL:
                return "一般";
            case PATCH_STATUS:
                return "Patch Status";
            case ROOT:
            default:
                return "Linimal";
        }
    }

    /**
     * ヘッダーはこの Activity が自前で描画するため、システムの ActionBar は表示しません。
     * manifest のテーマ（ActionBar なし）が効かない環境向けの保険です。
     */
    private void hideSystemActionBar() {
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
    }

    /**
     * ActionBar のないテーマでは system bar 領域が content と重なるため、root へ inset を padding として入れます。
     * 背景色は root が塗るので、status bar と navigation bar の領域も設定画面と同じ色で埋まります。
     */
    private void applyWindowInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void applySystemBars() {
        Window window = getWindow();
        if (window == null) {
            return;
        }
        // テーマの背景色は端末のダークモードに追従しないため、Window 背景も palette で塗ります。
        window.setBackgroundDrawable(new ColorDrawable(palette.background));
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.background);
        View decorView = window.getDecorView();
        int flags = decorView.getSystemUiVisibility();
        if (palette.dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        decorView.setSystemUiVisibility(flags);
    }

    private int toneColor(PatchStatusPresenter.Tone tone) {
        switch (tone) {
            case OK:
                return palette.statusOk;
            case WARNING:
                return palette.statusWarning;
            case ERROR:
                return palette.statusError;
            case NEUTRAL:
            default:
                return palette.secondaryText;
        }
    }

    private void applySwitchColors(Switch toggle, boolean enabled) {
        int checkedColor = enabled ? palette.accent : palette.disabledText;
        ColorStateList thumb = new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {checkedColor, palette.dark ? 0xFF8A8A8A : 0xFFFFFFFF});
        ColorStateList track = new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {checkedColor, palette.switchTrackOff});
        Drawable thumbDrawable = toggle.getThumbDrawable();
        if (thumbDrawable != null) {
            thumbDrawable.setTintList(thumb);
        }
        Drawable trackDrawable = toggle.getTrackDrawable();
        if (trackDrawable != null) {
            trackDrawable.setTintList(track);
        }
    }

    private Drawable rowRipple() {
        // mask を渡して行の範囲内に収めます（selectableItemBackground 相当）。
        return createRipple(new ColorDrawable(0xFFFFFFFF));
    }

    private Drawable borderlessRipple() {
        // mask なしの ripple は範囲外へ広がります（selectableItemBackgroundBorderless 相当）。
        return createRipple(null);
    }

    /**
     * ripple 色はテーマではなく palette から決めます。ActionBar なしのプラットフォームテーマは
     * 端末のダークモードに追従しないため、テーマ由来の色ではタッチ反応が見えなくなるためです。
     */
    private Drawable createRipple(Drawable mask) {
        int rippleColor = palette.dark ? 0x33FFFFFF : 0x1F000000;
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private float density() {
        return getResources().getDisplayMetrics().density;
    }

    private int dp(int value) {
        return Math.round(value * density());
    }
}
