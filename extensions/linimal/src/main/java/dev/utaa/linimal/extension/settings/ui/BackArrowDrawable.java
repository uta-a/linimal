package dev.utaa.linimal.extension.settings.ui;

/**
 * LINE の設定画面と同じ細い戻る矢印。新しい drawable resource を追加せずに描画します。
 */
public final class BackArrowDrawable extends StrokeChevronDrawable {
    public BackArrowDrawable(int color, float density) {
        super(color, density, POINTS_LEFT);
    }
}
