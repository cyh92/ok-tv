package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;

import com.google.android.material.textview.MaterialTextView;

public class MarqueeTextView extends MaterialTextView {

    private float offsetX;
    private float scrollSpeed = 2.0f;
    private float textWidth;
    private float gap = 80f;
    private boolean isMarqueeEnable = false;
    private float textBaseY;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isMarqueeEnable) {
                return;
            }
            int viewWidth = getWidth();
            if(viewWidth <= 0){
                handler.postDelayed(this, 16);
                return;
            }
            offsetX -= scrollSpeed;
            if(offsetX + textWidth < 0){
                offsetX = viewWidth;
            }
            invalidate();
            handler.postDelayed(this, 16);
        }
    };

    public MarqueeTextView(Context context) {
        super(context);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public MarqueeTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setEllipsize(null);
        setMaxLines(1);
        setWillNotDraw(false);
        offsetX = 0;
        isMarqueeEnable = true; // 默认打开滚动
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        Paint.FontMetrics fm = getPaint().getFontMetrics();
        textBaseY = h / 2f - (fm.descent + fm.ascent)/2f;
        measureTextWidth();
        offsetX = w;
        if(isMarqueeEnable){
            startScroll();
        }
    }

    private void measureTextWidth(){
        String text = getText().toString();
        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        textWidth = paint.measureText(text);
    }

    private void startScroll(){
        handler.removeCallbacks(scrollRunnable);
        handler.post(scrollRunnable);
    }

    private void stopScroll(){
        handler.removeCallbacks(scrollRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = getPaint();
        paint.setColor(getCurrentTextColor());
        if(!isMarqueeEnable){
            canvas.drawText(getText().toString(), 0, textBaseY, paint);
            return;
        }
        String content = getText().toString();
        canvas.drawText(content,offsetX,textBaseY,paint);
        canvas.drawText(content, offsetX + textWidth + gap, textBaseY,paint);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        measureTextWidth();
        if(getWidth()>0){
            offsetX = getWidth();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopScroll();
    }

    /**
     * 开启/关闭跑马灯滚动
     */
    public void enableMarquee(boolean enable){
        isMarqueeEnable = enable;
        if(enable){
            if(getWidth()>0) offsetX = getWidth();
            startScroll();
        }else {
            stopScroll();
        }
        invalidate();
    }

    /**
     * 设置滚动速度，推荐 1.5f ~ 3.0f
     */
    public void setScrollSpeed(float speed){
        scrollSpeed = speed;
    }

    /**
     * 设置两段文字之间空白间距(px)
     */
    public void setMarqueeGap(float gapPx){
        gap = gapPx;
    }
}
