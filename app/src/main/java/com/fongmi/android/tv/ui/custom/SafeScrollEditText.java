package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.google.android.material.textfield.TextInputEditText;

public class SafeScrollEditText extends TextInputEditText {

    private final Paint trackPaint;
    private final Paint thumbPaint;
    private final RectF rect;
    private final int barSize;
    private final int touchSize;
    private final int minThumb;
    private final int inset;
    private int dragMode;

    public SafeScrollEditText(Context context) {
        this(context, null);
    }

    public SafeScrollEditText(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.editTextStyle);
    }

    public SafeScrollEditText(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        rect = new RectF();
        barSize = dp(6);
        touchSize = dp(24);
        minThumb = dp(34);
        inset = dp(3);
        init();
    }

    private void init() {
        setHorizontalScrollBarEnabled(false);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setWillNotDraw(false);
        trackPaint.setColor(Color.parseColor("#DADCE0"));
        thumbPaint.setColor(Color.parseColor("#185ABC"));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawVerticalBar(canvas);
        drawHorizontalBar(canvas);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (handleScrollBarTouch(event)) return true;
        return super.onTouchEvent(event);
    }

    @Override
    protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
        invalidate();
    }

    @Override
    protected void onTextChanged(CharSequence text, int start, int before, int count) {
        super.onTextChanged(text, start, before, count);
        invalidate();
    }

    private void drawVerticalBar(Canvas canvas) {
        int range = computeVerticalScrollRange();
        int extent = computeVerticalScrollExtent();
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float viewportTop = getScrollY();
        float viewportBottom = viewportTop + getHeight();
        float left = getScrollX() + getWidth() - inset - barSize;
        float top = viewportTop + inset;
        float bottom = viewportBottom - inset - barSize - inset;
        float track = bottom - top;
        if (track <= 0) return;
        float thumb = range <= extent ? track : Math.max(minThumb, track * extent / range);
        float maxTop = Math.max(top, bottom - thumb);
        float offset = computeVerticalScrollOffset();
        float thumbTop = top + (maxTop - top) * offset / Math.max(1, range - extent);
        drawRound(canvas, left, top, left + barSize, bottom, trackPaint);
        drawRound(canvas, left, thumbTop, left + barSize, thumbTop + thumb, thumbPaint);
    }

    private void drawHorizontalBar(Canvas canvas) {
        int range = computeHorizontalScrollRange();
        int extent = computeHorizontalScrollExtent();
        if (getWidth() <= 0 || getHeight() <= 0) return;
        float viewportLeft = getScrollX();
        float viewportRight = viewportLeft + getWidth();
        float top = getScrollY() + getHeight() - inset - barSize;
        float left = viewportLeft + inset;
        float right = viewportRight - inset - barSize - inset;
        float track = right - left;
        if (track <= 0) return;
        float thumb = range <= extent ? track : Math.max(minThumb, track * extent / range);
        float maxLeft = Math.max(left, right - thumb);
        float offset = computeHorizontalScrollOffset();
        float thumbLeft = left + (maxLeft - left) * offset / Math.max(1, range - extent);
        drawRound(canvas, left, top, right, top + barSize, trackPaint);
        drawRound(canvas, thumbLeft, top, thumbLeft + thumb, top + barSize, thumbPaint);
    }

    private boolean handleScrollBarTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dragMode = hitMode(event.getX(), event.getY());
            if (dragMode == 0) return false;
            getParent().requestDisallowInterceptTouchEvent(true);
            scrollFromTouch(event.getX(), event.getY());
            return true;
        }
        if (dragMode == 0) return false;
        if (action == MotionEvent.ACTION_MOVE) {
            scrollFromTouch(event.getX(), event.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragMode = 0;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }

    private int hitMode(float x, float y) {
        if (x >= getWidth() - touchSize) return 1;
        if (y >= getHeight() - touchSize) return 2;
        return 0;
    }

    private void scrollFromTouch(float x, float y) {
        if (dragMode == 1) {
            int range = computeVerticalScrollRange();
            int extent = computeVerticalScrollExtent();
            if (range <= extent) return;
            float top = inset;
            float bottom = getHeight() - inset - barSize - inset;
            float ratio = clamp((y - top) / Math.max(1f, bottom - top));
            scrollTo(getScrollX(), Math.round((range - extent) * ratio));
        } else if (dragMode == 2) {
            int range = computeHorizontalScrollRange();
            int extent = computeHorizontalScrollExtent();
            if (range <= extent) return;
            float left = inset;
            float right = getWidth() - inset - barSize - inset;
            float ratio = clamp((x - left) / Math.max(1f, right - left));
            scrollTo(Math.round((range - extent) * ratio), getScrollY());
        }
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void drawRound(Canvas canvas, float left, float top, float right, float bottom, Paint paint) {
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, barSize, barSize, paint);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
