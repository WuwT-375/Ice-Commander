package dev.icecommander;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Pure Java filesystem/archive primitives; never follow links during recursive operations. */
public final class FileEngine {
  public static final int EDIT_LIMIT = 256 * 1024;

  public static byte[] bounded(InputStream in, int limit) throws IOException {
    try (InputStream input = in;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = input.read(b)) != -1) {
        if (out.size() + n > limit)
          throw new IOException("Файл слишком большой (лимит " + limit + " байт)");
        out.write(b, 0, n);
      }
      return out.toByteArray();
    }
  }

  public static String quote(String s) {
    if (s.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL в пути");
    return "'" + s.replace("'", "'\\''") + "'";
  }

  public static void validName(String s) {
    if (s.isEmpty() || s.equals(".") || s.equals("..") || s.contains("/") || s.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Недопустимое имя");
  }

  public static String encode(byte[] bytes, boolean binary) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < bytes.length; i++) {
      int n = bytes[i] & 255;
      String s = Integer.toString(n, binary ? 2 : 16).toUpperCase(Locale.ROOT);
      for (int pad = s.length(); pad < (binary ? 8 : 2); pad++) out.append("0");
      out.append(s).append((i + 1) % 16 == 0 ? '\n' : ' ');
    }
    return out.toString();
  }

  public static byte[] decode(String text, boolean binary) {
    String s = text.replaceAll("\\s", "");
    int width = binary ? 8 : 2;
    if (s.length() % width != 0 || !s.matches(binary ? "[01]*" : "[0-9a-fA-F]*"))
      throw new IllegalArgumentException("Каждый байт должен содержать " + width + " цифр");
    if (s.length() / width > EDIT_LIMIT)
      throw new IllegalArgumentException("Превышен лимит редактора");
    byte[] bytes = new byte[s.length() / width];
    for (int i = 0; i < bytes.length; i++)
      bytes[i] = (byte) Integer.parseInt(s.substring(i * width, (i + 1) * width), binary ? 2 : 16);
    return bytes;
  }

  public static void zip(File source, File target) throws IOException {
    if (target.toPath().normalize().startsWith(source.toPath().normalize()))
      throw new IOException("Архив нельзя создавать внутри исходной папки");
    try (OutputStream raw = Files.newOutputStream(target.toPath(), StandardOpenOption.CREATE_NEW);
        ZipOutputStream out = new ZipOutputStream(raw)) {
      addZip(source, source.getName(), out);
    } catch (IOException e) {
      throw e;
    }
  }

  private static void addZip(File f, String name, ZipOutputStream out) throws IOException {
    if (Files.isSymbolicLink(f.toPath()))
      throw new IOException("Символические ссылки не архивируются");
    out.putNextEntry(new ZipEntry(name + (f.isDirectory() ? "/" : "")));
    if (f.isFile()) {
      Files.copy(f.toPath(), out);
    }
    out.closeEntry();
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children == null) throw new IOException("Нет доступа: " + f);
      for (File child : children) addZip(child, name + "/" + child.getName(), out);
    }
  }

  public static List<String> entries(InputStream input) throws IOException {
    List<String> result = new ArrayList<>();
    try (ZipInputStream zip = new ZipInputStream(input)) {
      ZipEntry e;
      long total = 0;
      byte[] b = new byte[8192];
      while ((e = zip.getNextEntry()) != null) {
        if (result.size() >= 10000) throw new IOException("Слишком много записей");
        result.add(e.getName());
        int n;
        while ((n = zip.read(b)) != -1) {
          total += n;
          if (total > 1024L * 1024 * 1024) throw new IOException("Архив превышает лимит 1 ГиБ");
        }
      }
    }
    return result;
  }

  public static void unzip(InputStream input, File destination) throws IOException {
    // Require a new directory: existing files and symlinks are never overwritten.
    Files.createDirectory(destination.toPath());
    Path root = destination.toPath().toRealPath();
    long total = 0;
    int count = 0;
    try (ZipInputStream zip = new ZipInputStream(input)) {
      ZipEntry e;
      byte[] buf = new byte[8192];
      while ((e = zip.getNextEntry()) != null) {
        if (++count > 10000) throw new IOException("Слишком много записей");
        Path target = root.resolve(e.getName()).normalize();
        if (!target.startsWith(root) || target.equals(root) || e.getName().contains("\\"))
          throw new IOException("Небезопасный путь в ZIP");
        if (e.isDirectory()) {
          Files.createDirectories(target);
          continue;
        }
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
          int n;
          while ((n = zip.read(buf)) != -1) {
            total += n;
            if (total > 1024L * 1024 * 1024) throw new IOException("Распаковка превышает 1 ГиБ");
            out.write(buf, 0, n);
          }
        }
      }
    }
  }
}
