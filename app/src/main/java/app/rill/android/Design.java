package app.rill.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.TextView;

final class Design {
  final Context context;
  final boolean dark;
  final int bg, surface, ink, muted, accent, onAccent, soft, line;

  Design(Context c) {
    context = c;
    dark =
        (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
            == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    bg = Color.parseColor(dark ? "#101915" : "#F6F8F5");
    surface = Color.parseColor(dark ? "#1C2923" : "#FFFFFF");
    ink = Color.parseColor(dark ? "#E6EEE8" : "#172B24");
    muted = Color.parseColor(dark ? "#A3B5AA" : "#647369");
    accent = Color.parseColor(dark ? "#B9EACD" : "#245E49");
    onAccent = Color.parseColor(dark ? "#183729" : "#FFFFFF");
    soft = Color.parseColor(dark ? "#2B4235" : "#E2EFE5");
    line = Color.parseColor(dark ? "#334339" : "#E0E7E0");
  }

  int dp(float n) {
    return (int) (context.getResources().getDisplayMetrics().density * n + .5f);
  }

  boolean animate() {
    return ValueAnimator.areAnimatorsEnabled();
  }

  GradientDrawable rounded(int color, float radius) {
    GradientDrawable g = new GradientDrawable();
    g.setColor(color);
    g.setCornerRadius(dp(radius));
    return g;
  }

  TextView text(String value, float size, int color) {
    TextView t = new TextView(context);
    t.setText(value);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    t.setFontFeatureSettings("tnum");
    return t;
  }

  void press(View view) {
    view.setOnTouchListener(
        (v, e) -> {
          if (animate()) {
            if (e.getAction() == MotionEvent.ACTION_DOWN)
              v.animate().scaleX(.96f).scaleY(.96f).setDuration(100).start();
            if (e.getAction() == MotionEvent.ACTION_UP
                || e.getAction() == MotionEvent.ACTION_CANCEL)
              v.animate()
                  .scaleX(1)
                  .scaleY(1)
                  .setInterpolator(new OvershootInterpolator(1.3f))
                  .setDuration(260)
                  .start();
          }
          return false;
        });
  }

  TextView button(String title, boolean primary) {
    TextView t = text(title, 16, primary ? onAccent : ink);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    t.setGravity(Gravity.CENTER);
    GradientDrawable shape = rounded(primary ? accent : soft, 22);
    t.setBackground(
        new RippleDrawable(
            android.content.res.ColorStateList.valueOf(dark ? 0x33FFFFFF : 0x22000000),
            shape,
            null));
    t.setClickable(true);
    t.setFocusable(true);
    t.setMinHeight(dp(54));
    press(t);
    return t;
  }

  static class Icon extends View {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    final String name;
    int color;

    Icon(Context c, String name, int color) {
      super(c);
      this.name = name;
      this.color = color;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    protected void onDraw(Canvas raw) {
      super.onDraw(raw);
      Canvas c = raw;
      c.save();
      float size = Math.min(getWidth(), getHeight());
      c.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
      c.scale(size / 24, size / 24);
      paint.setColor(color);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      Path p = path;
      p.reset();
      switch (name) {
        case "flow":
          c.scale(24f / 108, 24f / 108);
          paint.setStrokeWidth(8);
          for (int i = 0; i < 3; i++) {
            float x = 30 + 18 * i;
            p.moveTo(x, 31);
            p.cubicTo(x + 21, 31, x - 9, 77, x + 12, 77);
          }
          c.drawPath(p, paint);
          break;
        case "config":
          p.moveTo(4, 5);
          p.lineTo(9, 5);
          p.lineTo(11, 8);
          p.lineTo(20, 8);
          p.lineTo(20, 19);
          p.lineTo(4, 19);
          p.close();
          c.drawPath(p, paint);
          break;
        case "log":
          c.drawRoundRect(5, 3, 19, 21, 3, 3, paint);
          c.drawLine(9, 8, 15, 8, paint);
          c.drawLine(9, 12, 15, 12, paint);
          c.drawLine(9, 16, 13, 16, paint);
          break;
        case "settings":
          for (int i = 0; i < 3; i++) {
            int y = 5 + i * 7;
            c.drawLine(4, y, 20, y, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            c.drawCircle(i == 1 ? 15 : 9, y, 2.6f, paint);
            paint.setStyle(Paint.Style.STROKE);
          }
          break;
        case "file":
          p.moveTo(6, 3);
          p.lineTo(14, 3);
          p.lineTo(19, 8);
          p.lineTo(19, 21);
          p.lineTo(6, 21);
          p.close();
          c.drawPath(p, paint);
          p.reset();
          p.moveTo(14, 3);
          p.lineTo(14, 8);
          p.lineTo(19, 8);
          c.drawPath(p, paint);
          break;
        case "link":
          c.rotate(-40, 12, 12);
          c.drawRoundRect(3, 8, 13, 16, 4, 4, paint);
          c.drawRoundRect(11, 8, 21, 16, 4, 4, paint);
          break;
        case "check":
          p.moveTo(5, 12);
          p.lineTo(10, 17);
          p.lineTo(20, 6);
          c.drawPath(p, paint);
          break;
      }
      c.restore();
    }
  }

  static class Flow extends View {
    final Design d;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final PathMeasure pathMeasure = new PathMeasure();
    private final float[] position = new float[2];
    float tint, phase;
    boolean active, moving;
    ValueAnimator animator, transition;

    Flow(Context c, Design d) {
      super(c);
      this.d = d;
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void setState(boolean on, boolean busy) {
      if (active != on) {
        active = on;
        if (transition != null) transition.cancel();
        if (d.animate()) {
          transition = ValueAnimator.ofFloat(tint, on ? 1 : 0);
          transition.setDuration(360);
          transition.addUpdateListener(
              a -> {
                tint = (float) a.getAnimatedValue();
                invalidate();
              });
          transition.start();
          animate()
              .scaleX(on ? 1.04f : 1)
              .scaleY(on ? 1.04f : 1)
              .setDuration(140)
              .withEndAction(
                  () ->
                      animate()
                          .scaleX(1)
                          .scaleY(1)
                          .setDuration(340)
                          .setInterpolator(new OvershootInterpolator(1.8f))
                          .start())
              .start();
        } else tint = on ? 1 : 0;
      }
      if (moving != busy) {
        moving = busy;
        updateAnimator();
      }
      invalidate();
    }

    private void updateAnimator() {
      if (animator != null) {
        animator.cancel();
        animator = null;
      }
      if (moving && d.animate() && getWindowVisibility() == VISIBLE && isShown()) {
        animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(2400);
        animator.setInterpolator(new LinearInterpolator());
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(
            a -> {
              phase = (float) a.getAnimatedValue();
              invalidate();
            });
        animator.start();
      }
    }

    protected void onWindowVisibilityChanged(int visibility) {
      super.onWindowVisibilityChanged(visibility);
      updateAnimator();
    }

    protected void onDetachedFromWindow() {
      if (animator != null) animator.cancel();
      if (transition != null) transition.cancel();
      super.onDetachedFromWindow();
    }

    protected void onDraw(Canvas c) {
      super.onDraw(c);
      float size = Math.min(getWidth(), getHeight());
      c.save();
      c.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
      c.scale(size / 240, size / 240);
      p.setStyle(Paint.Style.FILL);
      p.setColor(d.soft);
      c.drawRoundRect(20, 20, 220, 220, 68, 68, p);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(17);
      p.setStrokeCap(Paint.Cap.ROUND);
      int off = d.dark ? 0xFF648274 : 0xFFA6BCAD;
      p.setColor(blend(off, d.accent, tint));
      for (int i = 0; i < 3; i++) {
        float x = 64 + i * 39;
        path.reset();
        path.moveTo(x, 67);
        path.cubicTo(x + 48, 67, x - 20, 173, x + 28, 173);
        c.drawPath(path, p);
        if (moving && d.animate()) {
          PathMeasure m = pathMeasure;
          m.setPath(path, false);
          float[] xy = position;
          m.getPosTan(((phase + i * .18f) % 1) * m.getLength(), xy, null);
          p.setColor(d.dark ? 0xFFE4FFED : 0xFFB1D9B9);
          p.setStyle(Paint.Style.FILL);
          c.drawCircle(xy[0], xy[1], 4, p);
          p.setStyle(Paint.Style.STROKE);
          p.setColor(blend(off, d.accent, tint));
        }
      }
      c.restore();
    }

    private static int blend(int a, int b, float t) {
      return Color.rgb(
          (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t),
          (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t),
          (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }
  }
}
