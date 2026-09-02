package dev.utaa.linimal.extension.settings;

/**
 * 一つの Activity 内の Linimal 設定ページ遷移を管理します。
 *
 * <p>許可するパスは ROOT 単体、または ROOT と子ページ一つだけです。</p>
 */
public final class SettingsNavigation {
    /**
     * 戻る操作の後に呼び出し側が取るべき処理です。
     */
    public enum PopResult {
        /** 子ページから ROOT へ戻りました。 */
        RETURNED_TO_ROOT,
        /** ROOT 上のため、Activity を終了してください。 */
        FINISH_ACTIVITY
    }

    private SettingsPage currentPage = SettingsPage.ROOT;

    /**
     * 現在表示すべきページを返します。
     */
    public SettingsPage getCurrentPage() {
        return currentPage;
    }

    /**
     * ROOT の子ページを表示します。
     *
     * <p>既に子ページを表示中の場合、別の子ページを積み増して深い階層を作らないように
     * 遷移を拒否します。</p>
     *
     * @return 遷移した場合は true、拒否した場合は false
     */
    public boolean push(SettingsPage page) {
        if (page == null || page == SettingsPage.ROOT || currentPage != SettingsPage.ROOT) {
            return false;
        }

        currentPage = page;
        return true;
    }

    /**
     * 一段戻ります。
     *
     * @return ROOT へ戻ったこと、または Activity 終了が必要なことを表す結果
     */
    public PopResult pop() {
        if (currentPage == SettingsPage.ROOT) {
            return PopResult.FINISH_ACTIVITY;
        }

        currentPage = SettingsPage.ROOT;
        return PopResult.RETURNED_TO_ROOT;
    }

    /**
     * Bundle に依存しない形式で現在のパスを保存します。
     */
    public String[] serialize() {
        if (currentPage == SettingsPage.ROOT) {
            return new String[]{SettingsPage.ROOT.getId()};
        }
        return new String[]{SettingsPage.ROOT.getId(), currentPage.getId()};
    }

    /**
     * 保存済みのパスを復元します。
     *
     * <p>null、空、未知の識別子、ROOT が先頭にない値、または二階層を超える値は
     * 安全のため ROOT として扱います。</p>
     */
    public void restore(String[] serializedPath) {
        currentPage = restoredPage(serializedPath);
    }

    private static SettingsPage restoredPage(String[] serializedPath) {
        if (serializedPath == null || serializedPath.length == 0
                || !SettingsPage.ROOT.getId().equals(serializedPath[0])) {
            return SettingsPage.ROOT;
        }

        if (serializedPath.length != 2) {
            return SettingsPage.ROOT;
        }

        SettingsPage childPage = SettingsPage.fromId(serializedPath[1]);
        if (childPage == null || childPage == SettingsPage.ROOT) {
            return SettingsPage.ROOT;
        }
        return childPage;
    }
}
