package dev.utaa.linimal.extension.settings.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 細い線 2 本で「く」の字を描く drawable の共通実装です。頂点をどちら側に置くかだけが異なるため、
 * 向きを {@link #POINTS_LEFT} / {@link #POINTS_RIGHT} で受け取り、それ以外は同じ寸法で描画します。
 */
public abstract class StrokeChevronDrawable extends Drawable {
    /** 頂点を左に置きます。戻る矢印の向きです。 */
    protected static final float POINTS_LEFT = 1f;

    /** 頂点を右に置きます。詳細画面への遷移を示す chevron の向きです。 */
    protected static final float POINTS_RIGHT = -1f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    private final float direction;

    StrokeChevronDrawable(int color, float density, float direction) {
        this.density = density;
        this.direction = direction;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * density);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float centerX = bounds.exactCenterX();
        float centerY = bounds.exactCenterY();
        float half = 5.5f * density;
        float offsetX = direction * half * 0.6f;

        path.reset();
        path.moveTo(centerX + offsetX, centerY - half);
        path.lineTo(centerX - offsetX, centerY);
        path.lineTo(centerX + offsetX, centerY + half);
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return Math.round(24 * density);
    }

    @Override
    public int getIntrinsicHeight() {
        return Math.round(24 * density);
    }
}
