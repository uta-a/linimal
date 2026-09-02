package dev.utaa.linimal.extension.settings.ui;

/**
 * 設定項目の詳細画面への遷移を示す、細い右向きの chevron を描画します。
 */
public final class ChevronDrawable extends StrokeChevronDrawable {
    public ChevronDrawable(int color, float density) {
        super(color, density, POINTS_RIGHT);
    }
}
