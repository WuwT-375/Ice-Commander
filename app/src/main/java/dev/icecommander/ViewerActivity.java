package dev.icecommander;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

public class ViewerActivity extends Ui {
  Uri uri;
  EditText editor;
  String mode = "", name = "Файл";
  boolean dirty, loading, saving;
  VideoView video;
  int playback;
  String restoredText;
  int loadGeneration;

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    uri = getIntent().getData();
    if (uri == null && getIntent().hasExtra("path"))
      uri = Uri.fromFile(new File(getIntent().getStringExtra("path")));
    if (uri == null) {
      error(new IOException("Не передан файл"));
      return;
    }
    name = uri.getLastPathSegment();
    if ("content".equals(uri.getScheme())) {
      try (android.database.Cursor c =
          getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (c != null && c.moveToFirst()) name = c.getString(0);
      } catch (Exception ignored) {
      }
    }
    if (name == null) name = "Файл";
    String component = getIntent().getComponent().getClassName();
    String mime = getContentResolver().getType(uri);
    String lower = name.toLowerCase(Locale.ROOT);
    mode =
        component.endsWith("BinaryEditor")
            ? "hex"
            : component.endsWith("TextEditor")
                ? "text"
                : component.endsWith("ZipViewer") || lower.endsWith(".zip")
                    ? "zip"
                    : ((mime != null && mime.startsWith("image/"))
                            || lower.matches(".*\\.(jpg|jpeg|png|webp|gif|bmp|heic)$"))
                        ? "image"
                        : ((mime != null && mime.startsWith("video/"))
                                || lower.matches(".*\\.(mp4|mkv|webm|3gp|mov)$"))
                            ? "video"
                            : "text";
    if (b != null) {
      mode = b.getString("mode", mode);
      playback = b.getInt("playback");
      String draft = b.getString("draft");
      if (draft != null) {
        File f = new File(getCacheDir(), draft);
        try {
          restoredText =
              new String(
                  FileEngine.bounded(new FileInputStream(f), 3 * 1024 * 1024),
                  StandardCharsets.UTF_8);
          dirty = b.getBoolean("dirty");
        } catch (Exception e) {
          error(e);
        }
      }
    }
    render();
  }

  InputStream input() throws Exception {
    return "file".equals(uri.getScheme())
        ? new FileInputStream(uri.getPath())
        : getContentResolver().openInputStream(uri);
  }

  byte[] bytes() throws Exception {
    return "file".equals(uri.getScheme())
        ? Access.read(uri.getPath())
        : FileEngine.bounded(input(), FileEngine.EDIT_LIMIT);
  }

  void render() {
    int generation = ++loadGeneration;
    page();
    LinearLayout bar = row();
    bar.addView(icon("up", "Закрыть", this::onBackPressed));
    TextView title = text(name, 18);
    title.setMaxLines(2);
    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(bar);
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    LinearLayout modes = row();
    for (String m : new String[] {"text", "hex", "bin", "image", "video", "zip"})
      modes.addView(
          button(
              m.toUpperCase(Locale.ROOT),
              () -> {
                if (dirty) {
                  confirmDiscard(() -> switchMode(m));
                } else switchMode(m);
              }));
    scroll.addView(modes);
    root.addView(scroll);
    if (mode.equals("text") || mode.equals("hex") || mode.equals("bin")) {
      root.addView(
          text(
              mode.equals("text")
                  ? "UTF-8 · до 256 КиБ"
                  : "Байты · " + (mode.equals("bin") ? "8 бит" : "HEX") + " · до 256 КиБ",
              12));
      editor = input("");
      editor.setSingleLine(false);
      editor.setGravity(Gravity.TOP | Gravity.START);
      editor.setTypeface(Typeface.MONOSPACE);
      editor.setTextSize(14);
      editor.setHorizontallyScrolling(true);
      root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));
      editor.setEnabled(false);
      Button save =
          button(
              "Сохранить",
              () ->
                  new AlertDialog.Builder(this)
                      .setTitle("Записать изменения в файл?")
                      .setMessage(
                          "Содержимое будет заменено. Исходный поставщик должен разрешать запись.")
                      .setNegativeButton("Отмена", null)
                      .setPositiveButton("Сохранить", (d, w) -> save())
                      .show());
      root.addView(save);
      if (restoredText != null) {
        editor.setText(restoredText);
        restoredText = null;
        editor.setEnabled(true);
        watch();
      } else {
        String selected = mode;
        work(
            () -> {
              byte[] data = bytes();
              if (selected.equals("text"))
                return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(data))
                    .toString();
              return FileEngine.encode(data, selected.equals("bin"));
            },
            text -> {
              if (generation != loadGeneration) return;
              editor.setText(text);
              editor.setEnabled(true);
              dirty = false;
              watch();
            });
      }
    } else if (mode.equals("image")) {
      ImageView image = new ImageView(this);
      image.setAdjustViewBounds(true);
      image.setScaleType(ImageView.ScaleType.FIT_CENTER);
      root.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
      work(
          () -> {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = input()) {
              BitmapFactory.decodeStream(in, null, options);
            }
            int sample = 1;
            while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048)
              sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            try (InputStream in = input()) {
              Bitmap b = BitmapFactory.decodeStream(in, null, options);
              if (b == null) throw new IOException("Формат изображения не поддерживается");
              return b;
            }
          },
          image::setImageBitmap);
    } else if (mode.equals("video")) {
      video = new VideoView(this);
      root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
      MediaController controls = new MediaController(this);
      controls.setAnchorView(video);
      video.setMediaController(controls);
      video.setVideoURI(uri);
      video.setOnPreparedListener(
          mp -> {
            video.seekTo(playback);
            video.start();
          });
      video.setOnErrorListener(
          (mp, what, extra) -> {
            error(
                new IOException(
                    "Не удалось открыть видео. Проверьте доступ и поддержку кодека ("
                        + what
                        + ", "
                        + extra
                        + ")"));
            return true;
          });
    } else {
      TextView info = text("Чтение ZIP…", 14);
      ScrollView body = new ScrollView(this);
      body.addView(info);
      root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
      work(
          () -> FileEngine.entries(input()),
          entries -> info.setText(entries.isEmpty() ? "Пустой архив" : String.join("\n", entries)));
      root.addView(
          button(
              "Распаковать в новую папку",
              () ->
                  ask(
                      "Полный путь новой папки",
                      Environment.getExternalStorageDirectory() + "/Download/" + name + "-unpacked",
                      destination ->
                          work(
                              () -> {
                                FileEngine.unzip(input(), new File(destination));
                                return true;
                              },
                              ok ->
                                  new AlertDialog.Builder(this)
                                      .setMessage("Распаковано: " + destination)
                                      .setPositiveButton("OK", null)
                                      .show()))));
    }
  }

  void watch() {
    editor.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int count, int after) {}

          public void onTextChanged(CharSequence s, int st, int before, int count) {
            dirty = true;
          }

          public void afterTextChanged(android.text.Editable e) {}
        });
  }

  void switchMode(String next) {
    if (video != null) {
      video.stopPlayback();
      video = null;
    }
    dirty = false;
    mode = next;
    render();
  }

  void save() {
    if (saving || editor == null || !editor.isEnabled()) return;
    String content = editor.getText().toString();
    String selected = mode;
    saving = true;
    editor.setEnabled(false);
    work(
        () -> {
          try {
            byte[] data =
                selected.equals("text")
                    ? content.getBytes(StandardCharsets.UTF_8)
                    : FileEngine.decode(content, selected.equals("bin"));
            if (data.length > FileEngine.EDIT_LIMIT)
              throw new IOException("Лимит редактора: 256 КиБ");
            if ("file".equals(uri.getScheme())) Access.write(uri.getPath(), data, getCacheDir());
            else
              try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Поставщик не разрешил запись");
                out.write(data);
              }
            return true;
          } finally {
            runOnUiThread(
                () -> {
                  saving = false;
                  if (editor != null) editor.setEnabled(true);
                });
          }
        },
        ok -> {
          dirty = false;
          Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
        });
  }

  void confirmDiscard(Runnable action) {
    new AlertDialog.Builder(this)
        .setTitle("Есть несохранённые изменения")
        .setMessage("Отбросить изменения?")
        .setNegativeButton("Продолжить редактирование", null)
        .setPositiveButton("Отбросить", (d, w) -> action.run())
        .show();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putString("mode", mode);
    b.putBoolean("dirty", dirty);
    if (video != null) b.putInt("playback", video.getCurrentPosition());
    if (editor != null && (mode.equals("text") || mode.equals("hex") || mode.equals("bin"))) {
      try {
        File draft = File.createTempFile("editor-", ".draft", getCacheDir());
        java.nio.file.Files.write(
            draft.toPath(), editor.getText().toString().getBytes(StandardCharsets.UTF_8));
        b.putString("draft", draft.getName());
      } catch (IOException e) {
        error(e);
      }
    }
    super.onSaveInstanceState(b);
  }

  @Override
  protected void onPause() {
    if (video != null) {
      playback = video.getCurrentPosition();
      video.pause();
    }
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (video != null) video.stopPlayback();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (saving) return;
    if (dirty) confirmDiscard(this::finish);
    else finish();
  }
}
