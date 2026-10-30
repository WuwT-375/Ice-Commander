package dev.icecommander;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

class Ui extends Activity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  LinearLayout root;
  int bg, fg, accent;
  android.content.SharedPreferences prefs;

  int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    prefs = getSharedPreferences("ice", 0);
    palette();
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
  }

  void palette() {
    bg = prefs.getInt("background", Color.rgb(12, 23, 42));
    fg = Color.luminance(bg) > .45 ? Color.rgb(20, 35, 54) : Color.rgb(232, 243, 255);
    accent = prefs.getInt("accent", Color.rgb(125, 229, 237));
  }

  LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  void page() {
    palette();
    root = column();
    root.setPadding(dp(16), dp(8), dp(16), dp(8));
    root.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TL_BR, new int[] {bg, blend(bg, accent, .14f), bg}));
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          int left = insets.getSystemWindowInsetLeft(),
              top = insets.getSystemWindowInsetTop(),
              right = insets.getSystemWindowInsetRight(),
              bottom = insets.getSystemWindowInsetBottom();
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets i =
                insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
            left = i.left;
            top = i.top;
            right = i.right;
            bottom = i.bottom;
          }
          v.setPadding(dp(16) + left, dp(8) + top, dp(16) + right, dp(8) + bottom);
          return insets;
        });
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
  }

  static int blend(int a, int b, float x) {
    return Color.rgb(
        (int) (Color.red(a) * (1 - x) + Color.red(b) * x),
        (int) (Color.green(a) * (1 - x) + Color.green(b) * x),
        (int) (Color.blue(a) * (1 - x) + Color.blue(b) * x));
  }

  GradientDrawable glass() {
    GradientDrawable d = new GradientDrawable();
    d.setColor(Color.argb(22, Color.red(fg), Color.green(fg), Color.blue(fg)));
    d.setCornerRadius(dp(22));
    d.setStroke(dp(1), blend(bg, fg, .18f));
    return d;
  }

  TextView text(String s, int size) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(fg);
    t.setPadding(dp(10), dp(8), dp(10), dp(8));
    return t;
  }

  Button button(String s, Runnable action) {
    Button b = new Button(this);
    b.setText(s);
    b.setTextColor(accent);
    b.setAllCaps(false);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  EditText input(String s) {
    EditText e = new EditText(this);
    e.setText(s);
    e.setTextColor(fg);
    e.setSelectAllOnFocus(true);
    e.setSingleLine(true);
    return e;
  }

  ImageButton icon(String kind, String label, Runnable action) {
    ImageButton b = new ImageButton(this);
    b.setImageDrawable(new Glyph(kind, accent));
    b.setBackground(glass());
    b.setContentDescription(label);
    b.setTooltipText(label);
    b.setPadding(dp(12), dp(12), dp(12), dp(12));
    b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  void error(Throwable e) {
    new AlertDialog.Builder(this)
        .setTitle("Операция не выполнена")
        .setMessage(
            (e.getMessage() == null ? e.toString() : e.getMessage())
                + "\n\n"
                + "Проверьте путь и разрешения. Shell и root не отменяют ограничения SELinux и"
                + " прошивки.")
        .setPositiveButton("Понятно", null)
        .show();
  }

  interface Job<T> {
    T run() throws Exception;
  }

  <T> void work(Job<T> job, java.util.function.Consumer<T> done) {
    worker.execute(
        () -> {
          try {
            T result = job.run();
            runOnUiThread(
                () -> {
                  if (!isFinishing() && !isDestroyed()) done.accept(result);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (!isFinishing() && !isDestroyed()) error(e);
                });
          }
        });
  }

  void ask(String title, String value, java.util.function.Consumer<String> done) {
    EditText e = input(value);
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setView(e)
        .setNegativeButton("Отмена", null)
        .setPositiveButton(
            "OK",
            (d, w) -> {
              try {
                done.accept(e.getText().toString());
              } catch (Exception ex) {
                error(ex);
              }
            })
        .show();
  }

  @Override
  protected void onDestroy() {
    worker.shutdown();
    super.onDestroy();
  }

  static class Glyph extends Drawable {
    final Paint p = new Paint(3);
    final String kind;

    Glyph(String k, int color) {
      kind = k;
      p.setColor(color);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(1.7f);
      p.setStrokeCap(Paint.Cap.ROUND);
      p.setStrokeJoin(Paint.Join.ROUND);
    }

    public void draw(Canvas c) {
      c.save();
      c.translate(getBounds().left, getBounds().top);
      c.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      Path path = new Path();
      switch (kind) {
        case "folder":
          path.moveTo(3, 7);
          path.lineTo(9, 7);
          path.lineTo(11, 9);
          path.lineTo(21, 9);
          path.lineTo(21, 20);
          path.lineTo(3, 20);
          path.close();
          break;
        case "panels":
          c.drawRoundRect(3, 4, 21, 20, 2, 2, p);
          c.drawLine(12, 4, 12, 20, p);
          break;
        case "home":
          path.moveTo(3, 11);
          path.lineTo(12, 3);
          path.lineTo(21, 11);
          path.moveTo(6, 9);
          path.lineTo(6, 21);
          path.lineTo(18, 21);
          path.lineTo(18, 9);
          break;
        case "up":
          path.moveTo(5, 14);
          path.lineTo(12, 7);
          path.lineTo(19, 14);
          break;
        case "plus":
          c.drawLine(12, 4, 12, 20, p);
          c.drawLine(4, 12, 20, 12, p);
          break;
        case "settings":
          c.drawCircle(12, 12, 4, p);
          for (int i = 0; i < 8; i++) {
            c.save();
            c.rotate(i * 45, 12, 12);
            c.drawLine(12, 2, 12, 5, p);
            c.restore();
          }
          break;
        case "copy":
          c.drawRoundRect(8, 8, 21, 21, 2, 2, p);
          path.moveTo(16, 5);
          path.lineTo(4, 5);
          path.lineTo(4, 17);
          break;
        default:
          path.moveTo(6, 3);
          path.lineTo(15, 3);
          path.lineTo(20, 8);
          path.lineTo(20, 21);
          path.lineTo(6, 21);
          path.close();
          path.moveTo(10, 12);
          path.lineTo(16, 12);
          path.moveTo(10, 16);
          path.lineTo(16, 16);
      }
      c.drawPath(path, p);
      c.restore();
    }

    public void setAlpha(int a) {
      p.setAlpha(a);
    }

    public void setColorFilter(ColorFilter f) {
      p.setColorFilter(f);
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }
}
