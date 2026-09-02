package dev.utaa.linimal.extension.status;

import java.util.ArrayList;
import java.util.List;

/**
 * build-time の patch status を、画面に出す行へ変換します。
 * Android API に依存しないため local JVM test で検証できます。
 */
public final class PatchStatusPresenter {
    /** 行の意味づけ。配色の判断は画面側が行います。 */
    public enum Tone {
        OK,
        WARNING,
        ERROR,
        NEUTRAL
    }

    public static final class Row {
        private final String title;
        private final String detail;
        private final Tone tone;

        Row(String title, String detail, Tone tone) {
            this.title = title;
            this.detail = detail;
            this.tone = tone;
        }

        public String getTitle() {
            return title;
        }

        public String getDetail() {
            return detail;
        }

        public Tone getTone() {
            return tone;
        }
    }

    private PatchStatusPresenter() {
    }

    /** 読み取れなかった場合も、機能が有効だと誤認させない 1 行を返します。 */
    public static List<Row> rows(PatchStatusReadResult result) {
        List<Row> rows = new ArrayList<>();
        if (result == null) {
            rows.add(new Row("パッチ情報", "読み取れませんでした。", Tone.ERROR));
            return rows;
        }
        if (!result.isAvailable()) {
            Tone tone = result.getState() == PatchStatusReadResult.State.ERROR ? Tone.ERROR : Tone.NEUTRAL;
            rows.add(new Row("パッチ情報", reasonOrDefault(result), tone));
            return rows;
        }

        List<PatchStatusRecord> patches = result.getReport().getPatches();
        if (patches.isEmpty()) {
            rows.add(new Row("パッチ情報", "記録されたパッチがありません。", Tone.NEUTRAL));
            return rows;
        }
        for (PatchStatusRecord patch : patches) {
            rows.add(new Row(title(patch.getPatchId()), detail(patch), tone(patch.getStatus())));
        }
        return rows;
    }

    /** 適用状況を短い日本語にまとめます。対象数は補足として添えます。 */
    static String detail(PatchStatusRecord patch) {
        String label = label(patch.getStatus());
        if (patch.getExpectedTargetCount() == 0) {
            return label;
        }
        return label + " (" + patch.getActualTargetCount() + "/" + patch.getExpectedTargetCount() + ")";
    }

    /** patch ID の最後の要素だけを読みやすい見出しにします。 */
    static String title(String patchId) {
        String name = patchId;
        int separator = name.lastIndexOf('.');
        if (separator >= 0 && separator + 1 < name.length()) {
            name = name.substring(separator + 1);
        }
        String[] words = name.split("-");
        StringBuilder title = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (title.length() > 0) {
                title.append(' ');
            }
            title.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return title.length() == 0 ? patchId : title.toString();
    }

    static String label(PatchStatus status) {
        switch (status) {
            case OK:
                return "適用済み";
            case PARTIAL:
                return "一部のみ適用";
            case TARGET_NOT_FOUND:
                return "対象が見つかりません";
            case DISABLED:
                return "無効";
            default:
                return "エラー";
        }
    }

    static Tone tone(PatchStatus status) {
        switch (status) {
            case OK:
                return Tone.OK;
            case PARTIAL:
                return Tone.WARNING;
            case DISABLED:
                return Tone.NEUTRAL;
            default:
                return Tone.ERROR;
        }
    }

    private static String reasonOrDefault(PatchStatusReadResult result) {
        String reason = result.getReason();
        return reason == null || reason.isEmpty() ? "読み取れませんでした。" : reason;
    }
}
