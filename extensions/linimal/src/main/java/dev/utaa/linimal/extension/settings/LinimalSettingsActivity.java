package dev.utaa.linimal.extension.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import dev.utaa.linimal.extension.config.LinimalConfig;
import dev.utaa.linimal.extension.config.LinimalConfigBootstrap;
import dev.utaa.linimal.extension.config.LinimalConfigHealth;
import dev.utaa.linimal.extension.status.PatchStatus;
import dev.utaa.linimal.extension.status.PatchStatusReadResult;
import dev.utaa.linimal.extension.status.PatchStatusRecord;
import dev.utaa.linimal.extension.status.PatchStatusReport;
import dev.utaa.linimal.extension.status.PatchStatusRepository;

/** Linimal runtime 設定と build-time patch status を表示する framework-only の画面。 */
public final class LinimalSettingsActivity extends Activity {
    private TextView runtimeHealthView;
    private TextView premiumStatusView;
    private Switch premiumSwitch;
    private PatchStatus premiumBuildStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinimalConfigBootstrap.initialize(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(16);
        content.setPadding(padding, padding, padding, padding);

        addText(content, "Linimal 設定", 22);
        runtimeHealthView = addText(content, "", 16);

        addText(content, "Premium 誘導", 20);
        premiumSwitch = new Switch(this);
        premiumSwitch.setText("送信取消の Premium 案内を抑制");
        content.addView(premiumSwitch, matchWidthWrapContent());
        premiumStatusView = addText(content, "", 14);

        addText(content, "Patch Status", 20);
        PatchStatusReadResult statusResult = new PatchStatusRepository(this).read();
        renderPatchStatus(content, statusResult);
        premiumBuildStatus = premiumStatus(statusResult);
        renderRuntimeConfig();

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
    }

    private void renderPatchStatus(LinearLayout content, PatchStatusReadResult result) {
        if (!result.isAvailable()) {
            addText(
                    content,
                    "Patch Status: " + result.getState() + "\n" + result.getReason(),
                    14);
            return;
        }

        PatchStatusReport report = result.getReport();
        addText(content, "Schema version: " + report.getSchemaVersion(), 14);
        if (report.getPatches().isEmpty()) {
            addText(content, "記録された patch はありません。", 14);
            return;
        }
        for (PatchStatusRecord patch : report.getPatches()) {
            StringBuilder text = new StringBuilder()
                    .append(patch.getPatchId())
                    .append("\n")
                    .append(patch.getFeatureId())
                    .append(" · ")
                    .append(patch.getStatus())
                    .append(" (")
                    .append(patch.getActualTargetCount())
                    .append("/")
                    .append(patch.getExpectedTargetCount())
                    .append(")");
            if (patch.getReason() != null) {
                text.append("\n").append(patch.getReason());
            }
            addText(content, text.toString(), 14);
        }
    }

    private PatchStatus premiumStatus(PatchStatusReadResult result) {
        if (!result.isAvailable()) {
            return null;
        }
        return result.getReport().getPremiumStatus();
    }

    private void renderRuntimeConfig() {
        LinimalConfig config = LinimalConfig.get();
        LinimalConfigHealth health = config.getRuntimeHealth();
        runtimeHealthView.setText("Runtime config: " + health);

        boolean buildTimeReady = premiumBuildStatus == PatchStatus.OK;
        boolean configReady = health == LinimalConfigHealth.OK;
        boolean canChangePremium = buildTimeReady && configReady;
        String buildTimeStatus = premiumBuildStatus == null ? "UNAVAILABLE" : premiumBuildStatus.name();
        premiumStatusView.setText(
                "Build-time Premium unsend status: " + buildTimeStatus
                        + (canChangePremium ? "" : "\nこの設定は現在利用できません。"));

        // listener を一時的に外し、再描画時に意図しない永続化を起こさないようにします。
        premiumSwitch.setOnCheckedChangeListener(null);
        premiumSwitch.setEnabled(canChangePremium);
        premiumSwitch.setChecked(canChangePremium && config.isPremiumSuppressionEnabled());
        premiumSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                LinimalConfig.get().setPremiumSuppressionEnabled(isChecked);
                // 書き込み失敗時は config が fail-open に戻るため、直後の health と state を再描画します。
                renderRuntimeConfig();
            }
        });
    }

    private TextView addText(LinearLayout parent, String text, int textSizeSp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(textSizeSp);
        view.setPadding(0, dp(4), 0, dp(4));
        parent.addView(view, matchWidthWrapContent());
        return view;
    }

    private LinearLayout.LayoutParams matchWidthWrapContent() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
