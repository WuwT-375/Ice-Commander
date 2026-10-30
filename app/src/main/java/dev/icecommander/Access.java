package dev.icecommander;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import rikka.shizuku.Shizuku;

final class Access {
  static volatile IShell service;
  static String mode = "normal";
  static final Shizuku.UserServiceArgs ARGS =
      new Shizuku.UserServiceArgs(
              new ComponentName("dev.icecommander", ShellService.class.getName()))
          .daemon(false)
          .processNameSuffix("files")
          .version(1);
  static final ServiceConnection CONNECTION =
      new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
          service = IShell.Stub.asInterface(binder);
        }

        public void onServiceDisconnected(ComponentName name) {
          service = null;
        }
      };

  static void bind() {
    if (!Shizuku.pingBinder()) throw new IllegalStateException("Запустите Shizuku");
    if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
      Shizuku.requestPermission(7);
      return;
    }
    Shizuku.bindUserService(ARGS, CONNECTION);
  }

  static byte[] run(String command) throws Exception {
    if (mode.equals("shizuku")) {
      if (service == null)
        throw new IOException(
            "Shizuku не подключён. Разрешите доступ и подключитесь в настройках.");
      return service.run(command);
    }
    return ShellService.execute(
        mode.equals("su")
            ? new String[] {"su", "-c", command}
            : new String[] {"sh", "-c", command});
  }

  static String q(String s) {
    return FileEngine.quote(s);
  }

  static class Entry {
    String path, name;
    boolean directory;
    long size;

    Entry(String p, boolean d, long s) {
      path = p;
      name = new File(p).getName();
      directory = d;
      size = s;
    }
  }

  static List<Entry> list(String path, boolean hidden) throws Exception {
    String p = q(path);
    String script =
        "test -d "
            + p
            + " && test -r "
            + p
            + " && test -x "
            + p
            + " || { echo 'Нет доступа к папке' >&2; exit 1; }; for f in "
            + p
            + "/* "
            + p
            + "/.[!.]* "
            + p
            + "/..?*; do [ -e \"$f\" ] || continue; if [ -d \"$f\" ]; then d=d; else d=f; fi;"
            + " printf '%s\\0%s\\0' \"$f\" \"$d\"; stat -c '%s' -- \"$f\" | tr '\\n"
            + "' '\\0'; done";
    String[] items = new String(run(script), StandardCharsets.UTF_8).split("\u0000");
    List<Entry> out = new ArrayList<>();
    for (int i = 0; i + 2 < items.length; i += 3) {
      Entry e = new Entry(items[i], items[i + 1].equals("d"), Long.parseLong(items[i + 2]));
      if (hidden || !e.name.startsWith(".")) out.add(e);
    }
    out.sort(
        Comparator.<Entry>comparingInt(e -> e.directory ? 0 : 1)
            .thenComparing(e -> e.name.toLowerCase(Locale.ROOT)));
    return out;
  }

  static byte[] read(String path) throws Exception {
    byte[] b = run("head -c " + (FileEngine.EDIT_LIMIT + 1) + " -- " + q(path));
    if (b.length > FileEngine.EDIT_LIMIT) throw new IOException("Редактор: лимит 256 КиБ");
    return b;
  }

  static void write(String path, byte[] bytes, File cache) throws Exception {
    if (mode.equals("normal")) {
      java.nio.file.Files.write(new File(path).toPath(), bytes);
      return;
    }
    // Chunked base64 avoids Binder transaction limits and command-line length limits.
    String temp = path + ".ice-" + UUID.randomUUID();
    try {
      run("umask 077; set -C; : > " + q(temp));
      for (int i = 0; i < bytes.length; i += 8192) {
        String b =
            android.util.Base64.encodeToString(
                Arrays.copyOfRange(bytes, i, Math.min(i + 8192, bytes.length)),
                android.util.Base64.NO_WRAP);
        run("printf %s " + q(b) + " | base64 -d >> " + q(temp));
      }
      run("cat " + q(temp) + " > " + q(path));
    } finally {
      run("rm -f -- " + q(temp));
    }
  }

  static void operation(String op, String source, String target) throws Exception {
    String s = q(source), t = q(target);
    if (op.equals("delete")) {
      if (source.equals("/") || source.trim().isEmpty())
        throw new IOException("Нельзя удалить корень");
      run("rm -r -- " + s);
      return;
    }
    if (op.equals("mkdir")) {
      run("mkdir -- " + t);
      return;
    }
    if (op.equals("create")) {
      run("set -C; : > " + t);
      return;
    }
    String canonicalSource = new File(source).getCanonicalPath(),
        canonicalTarget = new File(target).getCanonicalPath();
    if (canonicalTarget.equals(canonicalSource)
        || canonicalTarget.startsWith(canonicalSource + "/"))
      throw new IOException("Нельзя поместить объект внутрь самого себя");
    run(
        "if [ -e "
            + t
            + " ] || [ -L "
            + t
            + " ]; then echo 'Объект уже существует' >&2; exit 1; fi; "
            + (op.equals("copy") ? "cp -RPn -- " : "mv -n -- ")
            + s
            + " "
            + t);
  }
}
