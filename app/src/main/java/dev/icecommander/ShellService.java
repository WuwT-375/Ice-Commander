package dev.icecommander;

import java.io.*;
import java.util.concurrent.*;

public class ShellService extends IShell.Stub {
  public ShellService() {}

  public ShellService(android.content.Context context) {}

  @Override
  public byte[] run(String command) {
    try {
      return execute(new String[] {"sh", "-c", command});
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage());
    }
  }

  static byte[] execute(String[] args) throws Exception {
    Process p = new ProcessBuilder(args).start();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    Future<byte[]> output = pool.submit(() -> FileEngine.bounded(p.getInputStream(), 512 * 1024));
    Future<byte[]> error = pool.submit(() -> FileEngine.bounded(p.getErrorStream(), 16 * 1024));
    try {
      if (!p.waitFor(90, TimeUnit.SECONDS)) throw new IOException("Операция превысила 90 секунд");
      byte[] result = output.get(5, TimeUnit.SECONDS);
      String message =
          new String(error.get(5, TimeUnit.SECONDS), java.nio.charset.StandardCharsets.UTF_8);
      if (p.exitValue() != 0)
        throw new IOException(
            message.trim().isEmpty() ? "Нет доступа или операция не поддерживается" : message);
      return result;
    } finally {
      p.destroy();
      pool.shutdownNow();
    }
  }
}
