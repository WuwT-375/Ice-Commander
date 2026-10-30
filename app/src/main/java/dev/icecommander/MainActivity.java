package dev.icecommander;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import rikka.shizuku.Shizuku;

public class MainActivity extends Ui {
  String[] paths = {"", ""};
  boolean dual;
  int active;
  String clipboard;
  boolean cut;
  int generation;
  TextView status;
  final Shizuku.OnRequestPermissionResultListener permission =
      (code, result) -> {
        if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
          try {
            Access.bind();
          } catch (Exception e) {
            error(e);
          }
        } else error(new SecurityException("Доступ Shizuku отклонён"));
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    dual = prefs.getBoolean("dual", false);
    if (state != null) {
      paths = state.getStringArray("paths");
      active = state.getInt("active");
      clipboard = state.getString("clipboard");
      cut = state.getBoolean("cut");
    } else {
      paths[0] = prefs.getString("left", "");
      paths[1] = prefs.getString("right", "");
    }
    Shizuku.addRequestPermissionResultListener(permission);
    show();
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    b.putStringArray("paths", paths);
    b.putInt("active", active);
    b.putString("clipboard", clipboard);
    b.putBoolean("cut", cut);
    super.onSaveInstanceState(b);
  }

  @Override
  protected void onPause() {
    prefs.edit().putString("left", paths[0]).putString("right", paths[1]).apply();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    Shizuku.removeRequestPermissionResultListener(permission);
    super.onDestroy();
  }

  void show() {
    int token = ++generation;
    page();
    LinearLayout header = row();
    TextView title = text("ICE  /  Commander", 22);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(
        icon(
            "panels",
            dual ? "Одна панель" : "Две панели",
            () -> {
              dual = !dual;
              active = 0;
              prefs.edit().putBoolean("dual", dual).apply();
              show();
            }));
    header.addView(icon("settings", "Настройки", this::settings));
    root.addView(header);
    status = text("ФАЙЛЫ  •  " + Access.mode.toUpperCase(Locale.ROOT), 11);
    root.addView(status);
    LinearLayout panels = new LinearLayout(this);
    boolean wide = getResources().getConfiguration().screenWidthDp >= 600;
    panels.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    root.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
    for (int i = 0; i < (dual ? 2 : 1); i++) {
      final int index = i;
      LinearLayout panel = column();
      panel.setBackground(glass());
      panel.setPadding(dp(6), dp(6), dp(6), dp(6));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(wide ? 0 : -1, wide ? -1 : 0, 1);
      lp.setMargins(dp(3), dp(3), dp(3), dp(3));
      panels.addView(panel, lp);
      LinearLayout nav = row();
      nav.addView(icon("home", "Главная", () -> navigate(index, "")));
      nav.addView(
          icon(
              "up",
              "На уровень вверх",
              () ->
                  navigate(
                      index,
                      paths[index].isEmpty()
                          ? ""
                          : Objects.toString(new File(paths[index]).getParent(), ""))));
      EditText address = input(paths[i].isEmpty() ? "Главная" : paths[i]);
      address.setTextSize(13);
      address.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
      address.setOnFocusChangeListener(
          (v, focused) -> {
            if (focused) {
              active = index;
              address.selectAll();
            }
          });
      address.setOnEditorActionListener(
          (v, id, event) -> {
            navigate(index, address.getText().toString());
            return true;
          });
      nav.addView(address, new LinearLayout.LayoutParams(0, -2, 1));
      nav.addView(
          icon(
              "copy",
              "Копировать адрес",
              () ->
                  ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                      .setPrimaryClip(ClipData.newPlainText("Путь", paths[index]))));
      panel.addView(nav);
      LinearLayout actions = row();
      actions.addView(button("Перейти", () -> navigate(index, address.getText().toString())));
      actions.addView(
          button(
              "Вставить путь",
              () -> {
                android.content.ClipboardManager c =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (c.hasPrimaryClip()) {
                  address.setText(c.getPrimaryClip().getItemAt(0).coerceToText(this));
                  address.requestFocus();
                }
              }));
      actions.addView(button("+", () -> create(index)));
      panel.addView(actions);
      if (clipboard != null)
        panel.addView(
            button(
                (cut ? "Переместить" : "Копировать") + " сюда: " + new File(clipboard).getName(),
                () -> paste(index)));
      if (paths[i].isEmpty()) {
        ScrollView homeScroll = new ScrollView(this);
        LinearLayout homeBody = column();
        homeScroll.addView(homeBody);
        panel.addView(homeScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        home(homeBody, index);
        continue;
      }
      TextView loading = text("Загрузка…", 14);
      panel.addView(loading);
      work(
          () -> Access.list(paths[index], prefs.getBoolean("hidden", false)),
          entries -> {
            if (token != generation) return;
            panel.removeView(loading);
            ListView list = new ListView(this);
            list.setDivider(null);
            list.setAdapter(
                new BaseAdapter() {
                  public int getCount() {
                    return entries.size();
                  }

                  public Object getItem(int p) {
                    return entries.get(p);
                  }

                  public long getItemId(int p) {
                    return p;
                  }

                  public View getView(int p, View reuse, android.view.ViewGroup parent) {
                    Access.Entry e = entries.get(p);
                    LinearLayout r = row();
                    r.setPadding(dp(6), dp(4), dp(6), dp(4));
                    ImageView glyph = new ImageView(MainActivity.this);
                    glyph.setImageDrawable(new Glyph(e.directory ? "folder" : "file", accent));
                    r.addView(glyph, new LinearLayout.LayoutParams(dp(30), dp(30)));
                    LinearLayout labels = column();
                    labels.addView(text(e.name, prefs.getInt("font", 16)));
                    TextView meta =
                        text(
                            e.directory
                                ? "Папка"
                                : android.text.format.Formatter.formatShortFileSize(
                                    MainActivity.this, e.size),
                            11);
                    meta.setAlpha(.6f);
                    labels.addView(meta);
                    r.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    return r;
                  }
                });
            list.setOnItemClickListener(
                (a, v, pos, id) -> {
                  active = index;
                  Access.Entry e = entries.get(pos);
                  if (e.directory) navigate(index, e.path);
                  else open(e.path);
                });
            list.setOnItemLongClickListener(
                (a, v, pos, id) -> {
                  active = index;
                  menu(entries.get(pos), index);
                  return true;
                });
            panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
            if (entries.isEmpty()) panel.addView(text("Папка пуста · нажмите + для создания", 14));
            list.setAlpha(0);
            list.animate().alpha(1).setDuration(220).start();
          });
    }
    root.addView(text("Удерживайте файл для действий", 12));
  }

  void navigate(int i, String path) {
    if (!path.isEmpty() && !path.equals("Главная") && !new File(path).isAbsolute()) {
      error(new IOException("Введите абсолютный путь, начиная с /"));
      return;
    }
    active = i;
    paths[i] = path.equals("Главная") ? "" : path;
    show();
  }

  void home(LinearLayout panel, int i) {
    panel.addView(text("Ваше пространство", 26));
    panel.addView(text("Две панели. Один поток.", 14));
    String storage = Environment.getExternalStorageDirectory().getAbsolutePath();
    String[][] places = {
      {"Внутренняя память", storage},
      {"Загрузки", storage + "/Download"},
      {"Изображения", storage + "/Pictures"},
      {"Корень устройства", "/"},
      {"Файлы приложения", getFilesDir().getAbsolutePath()}
    };
    for (String[] p : places) panel.addView(button(p[0], () -> navigate(i, p[1])));
    panel.addView(
        button(
            "Разрешить доступ к файлам",
            () -> {
              try {
                if (Build.VERSION.SDK_INT >= 30)
                  startActivity(
                      new Intent(
                          Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                          Uri.parse("package:" + getPackageName())));
                else
                  requestPermissions(
                      new String[] {
                        "android.permission.READ_EXTERNAL_STORAGE",
                        "android.permission.WRITE_EXTERNAL_STORAGE"
                      },
                      1);
              } catch (Exception e) {
                error(e);
              }
            }));
  }

  void open(String path) {
    startActivity(new Intent(this, ViewerActivity.class).putExtra("path", path));
  }

  void create(int index) {
    if (paths[index].isEmpty()) {
      error(new IOException("Сначала откройте папку"));
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Создать")
        .setItems(
            new String[] {"Папку", "Файл"},
            (d, which) ->
                ask(
                    "Имя",
                    "",
                    name -> {
                      FileEngine.validName(name);
                      op(
                          which == 0 ? "mkdir" : "create",
                          "",
                          new File(paths[index], name).getPath());
                    }))
        .show();
  }

  void op(String op, String src, String dst) {
    status.setText("Выполняется…");
    work(
        () -> {
          Access.operation(op, src, dst);
          return true;
        },
        ok -> show());
  }

  void paste(int i) {
    if (paths[i].isEmpty()) return;
    String src = clipboard;
    boolean moving = cut;
    String dst = new File(paths[i], new File(src).getName()).getPath();
    work(
        () -> {
          Access.operation(moving ? "move" : "copy", src, dst);
          return true;
        },
        ok -> {
          clipboard = null;
          show();
        });
  }

  void menu(Access.Entry e, int i) {
    new AlertDialog.Builder(this)
        .setTitle(e.name)
        .setItems(
            new String[] {
              "Открыть", "Переименовать", "Копировать", "Переместить", "Удалить", "Сжать в ZIP"
            },
            (d, w) -> {
              switch (w) {
                case 0:
                  if (e.directory) navigate(i, e.path);
                  else open(e.path);
                  break;
                case 1:
                  ask(
                      "Новое имя",
                      e.name,
                      n -> {
                        FileEngine.validName(n);
                        op("move", e.path, new File(new File(e.path).getParent(), n).getPath());
                      });
                  break;
                case 2:
                case 3:
                  clipboard = e.path;
                  cut = w == 3;
                  show();
                  break;
                case 4:
                  new AlertDialog.Builder(this)
                      .setTitle("Удалить безвозвратно?")
                      .setMessage(e.path)
                      .setNegativeButton("Отмена", null)
                      .setPositiveButton("Удалить", (a, b) -> op("delete", e.path, ""))
                      .show();
                  break;
                case 5:
                  ask(
                      "Имя ZIP",
                      e.name + ".zip",
                      n -> {
                        FileEngine.validName(n);
                        work(
                            () -> {
                              FileEngine.zip(
                                  new File(e.path), new File(new File(e.path).getParent(), n));
                              return true;
                            },
                            ok -> show());
                      });
              }
            })
        .show();
  }

  void settings() {
    String[] items = {
      "Тема: полярная ночь",
      "Тема: ледник",
      "Тема: аметист",
      "Создать свою тему",
      "Сохранённые темы",
      "Размер текста",
      "Скрытые файлы: " + prefs.getBoolean("hidden", false),
      "Обычный доступ",
      "Запросить SU / root",
      "Подключить Shizuku / shell"
    };
    new AlertDialog.Builder(this)
        .setTitle("Настройки Ice")
        .setItems(
            items,
            (d, w) -> {
              switch (w) {
                case 0:
                  setThemeColors(0xff0c172a, 0xff7de5ed);
                  break;
                case 1:
                  setThemeColors(0xffedf6fa, 0xff006d82);
                  break;
                case 2:
                  setThemeColors(0xff20172f, 0xffd8b4fe);
                  break;
                case 3:
                  ask(
                      "Название темы",
                      "Моя тема",
                      name ->
                          ask(
                              "Фон #RRGGBB",
                              "#10223B",
                              background ->
                                  ask(
                                      "Акцент #RRGGBB",
                                      "#7DE5ED",
                                      color -> {
                                        int b = android.graphics.Color.parseColor(background),
                                            a = android.graphics.Color.parseColor(color);
                                        Set<String> themes =
                                            new HashSet<>(
                                                prefs.getStringSet("themes", new HashSet<>()));
                                        themes.add(name + "|" + b + "|" + a);
                                        prefs.edit().putStringSet("themes", themes).apply();
                                        setThemeColors(b, a);
                                      })));
                  break;
                case 4:
                  String[] themes =
                      prefs.getStringSet("themes", new HashSet<>()).toArray(new String[0]);
                  String[] labels = new String[themes.length];
                  for (int index = 0; index < themes.length; index++) {
                    String item = themes[index];
                    labels[index] =
                        item.substring(0, item.lastIndexOf("|", item.lastIndexOf("|") - 1));
                  }
                  new AlertDialog.Builder(this)
                      .setTitle("Мои темы")
                      .setItems(
                          labels,
                          (a, b) -> {
                            String[] parts = themes[b].split("\\|");
                            setThemeColors(
                                Integer.parseInt(parts[parts.length - 2]),
                                Integer.parseInt(parts[parts.length - 1]));
                          })
                      .show();
                  break;
                case 5:
                  ask(
                      "Размер текста (12–24)",
                      "16",
                      s -> {
                        int n = Integer.parseInt(s);
                        if (n < 12 || n > 24) throw new IllegalArgumentException("От 12 до 24");
                        prefs.edit().putInt("font", n).apply();
                        show();
                      });
                  break;
                case 6:
                  prefs.edit().putBoolean("hidden", !prefs.getBoolean("hidden", false)).apply();
                  show();
                  break;
                case 7:
                  Access.mode = "normal";
                  show();
                  break;
                case 8:
                  work(
                      () -> {
                        byte[] result = ShellService.execute(new String[] {"su", "-c", "id -u"});
                        if (!new String(result).trim().equals("0"))
                          throw new IOException("SU не предоставил root");
                        return true;
                      },
                      ok -> {
                        Access.mode = "su";
                        show();
                      });
                  break;
                case 9:
                  try {
                    Access.bind();
                    Access.mode = "shizuku";
                    show();
                  } catch (Exception e) {
                    error(e);
                  }
                  break;
              }
            })
        .show();
  }

  void setThemeColors(int b, int a) {
    prefs.edit().putInt("background", b).putInt("accent", a).apply();
    show();
  }

  @Override
  public void onBackPressed() {
    if (!paths[active].isEmpty())
      navigate(active, Objects.toString(new File(paths[active]).getParent(), ""));
    else super.onBackPressed();
  }
}
