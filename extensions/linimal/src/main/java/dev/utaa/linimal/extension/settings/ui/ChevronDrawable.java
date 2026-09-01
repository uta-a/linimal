package dev.utaa.linimal.extension.settings.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 設定項目の詳細画面への遷移を示す、細い右向きの chevron を描画します。
 */
public final class ChevronDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;

    public ChevronDrawable(int color, float density) {
        this.density = density;
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

        path.reset();
        path.moveTo(centerX - half * 0.6f, centerY - half);
        path.lineTo(centerX + half * 0.6f, centerY);
        path.lineTo(centerX - half * 0.6f, centerY + half);
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
