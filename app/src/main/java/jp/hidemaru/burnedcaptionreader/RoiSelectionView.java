package jp.hidemaru.burnedcaptionreader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public final class RoiSelectionView extends View {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageRect = new RectF();
    private Bitmap bitmap;
    private RectF selection = new RectF(0.05f, 0.55f, 0.95f, 0.90f);
    private float anchorX;
    private float anchorY;

    public RoiSelectionView(Context context) {
        super(context);
        dimPaint.setColor(0x99000000);
        borderPaint.setColor(Color.rgb(255, 214, 10));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(3));
        setBackgroundColor(Color.BLACK);
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
        invalidate();
    }

    public void setSelection(RectF normalized) {
        if (normalized != null) selection = new RectF(normalized);
        invalidate();
    }

    public RectF getSelection() {
        return new RectF(selection);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;

        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = (getWidth() - width) / 2f;
        float top = (getHeight() - height) / 2f;
        imageRect.set(left, top, left + width, top + height);
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint);

        RectF selected = toViewRect(selection);
        canvas.drawRect(imageRect.left, imageRect.top, imageRect.right, selected.top, dimPaint);
        canvas.drawRect(imageRect.left, selected.bottom, imageRect.right, imageRect.bottom, dimPaint);
        canvas.drawRect(imageRect.left, selected.top, selected.left, selected.bottom, dimPaint);
        canvas.drawRect(selected.right, selected.top, imageRect.right, selected.bottom, dimPaint);
        canvas.drawRect(selected, borderPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (bitmap == null || imageRect.isEmpty()) return false;
        float x = clamp((event.getX() - imageRect.left) / imageRect.width());
        float y = clamp((event.getY() - imageRect.top) / imageRect.height());
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                anchorX = x;
                anchorY = y;
                selection.set(x, y, x, y);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                selection.set(Math.min(anchorX, x), Math.min(anchorY, y), Math.max(anchorX, x), Math.max(anchorY, y));
                invalidate();
                return true;
            default:
                return true;
        }
    }

    public boolean hasUsableSelection() {
        return selection.width() >= 0.05f && selection.height() >= 0.03f;
    }

    private RectF toViewRect(RectF normalized) {
        return new RectF(
                imageRect.left + normalized.left * imageRect.width(),
                imageRect.top + normalized.top * imageRect.height(),
                imageRect.left + normalized.right * imageRect.width(),
                imageRect.top + normalized.bottom * imageRect.height()
        );
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
