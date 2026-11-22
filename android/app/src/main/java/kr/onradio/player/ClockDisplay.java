package kr.onradio.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

final class ClockDisplay extends View {
    private static final float DISPLAY_WIDTH = 132;
    private static final int[] MASKS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};
    private static final float[][] SEGMENTS = {
        {2.5f, 2, 8.5f, 2}, {9.5f, 3.5f, 9.5f, 8.5f}, {9.5f, 11.5f, 9.5f, 16.5f},
        {2.5f, 18, 8.5f, 18}, {1.5f, 11.5f, 1.5f, 16.5f}, {1.5f, 3.5f, 1.5f, 8.5f},
        {2.5f, 10, 8.5f, 10}
    };
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private String digits = "00:00:00";
    private boolean timer;
    private String meridiem = "";

    ClockDisplay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void showTime(String digits, boolean timer, String description, String meridiem) {
        this.digits = digits;
        this.timer = timer;
        this.meridiem = meridiem;
        setContentDescription((description + " " + digits + " " + meridiem).trim());
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float inset = density / 2;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff121a14);
        canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, 5 * density, 5 * density, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xff4b5540);
        paint.setStrokeWidth(density);
        canvas.drawRoundRect(inset, inset, getWidth() - inset, getHeight() - inset, 5 * density, 5 * density, paint);
        float scale = Math.min((getWidth() - 10 * density) / DISPLAY_WIDTH, (getHeight() - 10 * density) / 20);
        if (scale <= 0) return;
        canvas.save();
        canvas.translate((getWidth() - DISPLAY_WIDTH * scale) / 2, (getHeight() - 20 * scale) / 2);
        canvas.scale(scale, scale);
        int lit = timer ? 0xffefc36b : 0xffd7ee7c;
        paint.setColor(lit);
        paint.setStrokeWidth(1.25f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawCircle(9, 11, 5.8f, paint);
        canvas.drawLine(9, 7.5f, 9, 11, paint);
        canvas.drawLine(9, 11, 12, 12.5f, paint);
        if (timer) {
            canvas.drawLine(9, 5, 9, 2.5f, paint);
            canvas.drawLine(7, 2.5f, 11, 2.5f, paint);
            canvas.drawLine(13.5f, 5.5f, 15, 4, paint);
        }
        float x = 21;
        for (int i = 0; i < digits.length(); i++) {
            char digit = digits.charAt(i);
            if (digit == ':') {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(lit);
                canvas.drawCircle(x + 1.5f, 7, 1, paint);
                canvas.drawCircle(x + 1.5f, 13, 1, paint);
                x += 6;
                continue;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.15f);
            int mask = MASKS[digit - '0'];
            for (int j = 0; j < SEGMENTS.length; j++) {
                float[] segment = SEGMENTS[j];
                paint.setColor((mask & (1 << j)) != 0 ? lit : 0xff303626);
                canvas.drawLine(x + segment[0], segment[1], x + segment[2], segment[3], paint);
            }
            x += 13;
        }
        if (!meridiem.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(lit);
            paint.setTypeface(Typeface.DEFAULT_BOLD);
            paint.setTextSize(9);
            canvas.drawText(meridiem, 114, 13, paint);
        }
        canvas.restore();
    }
}
