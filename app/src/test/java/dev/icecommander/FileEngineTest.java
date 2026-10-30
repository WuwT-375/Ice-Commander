package dev.icecommander;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.Test;

public class FileEngineTest {
  @Test
  public void byteRoundtrip() {
    byte[] all = new byte[256];
    for (int i = 0; i < 256; i++) all[i] = (byte) i;
    assertArrayEquals(all, FileEngine.decode(FileEngine.encode(all, false), false));
    assertArrayEquals(all, FileEngine.decode(FileEngine.encode(all, true), true));
  }

  @Test
  public void rejectsInvalidBytes() {
    for (String s : new String[] {"0", "GG", "123"}) {
      try {
        FileEngine.decode(s, false);
        fail();
      } catch (IllegalArgumentException expected) {
      }
    }
  }

  @Test
  public void quotesShell() {
    assertEquals("'a'\\''b'", FileEngine.quote("a'b"));
  }

  @Test
  public void roundtripArchive() throws Exception {
    Path root = Files.createTempDirectory("ice-test");
    File src = root.resolve("folder").toFile();
    assertTrue(src.mkdir());
    Files.write(
        src.toPath().resolve("a.txt"), "Лёд".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    File archive = root.resolve("test.zip").toFile();
    FileEngine.zip(src, archive);
    assertEquals(2, FileEngine.entries(new FileInputStream(archive)).size());
    File out = root.resolve("out").toFile();
    FileEngine.unzip(new FileInputStream(archive), out);
    assertEquals(
        "Лёд",
        new String(
            Files.readAllBytes(out.toPath().resolve("folder/a.txt")),
            java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  public void blocksZipSlip() throws Exception {
    Path root = Files.createTempDirectory("ice-slip");
    ByteArrayOutputStream raw = new ByteArrayOutputStream();
    try (ZipOutputStream out = new ZipOutputStream(raw)) {
      out.putNextEntry(new ZipEntry("../escaped"));
      out.write(1);
      out.closeEntry();
    }
    try {
      FileEngine.unzip(new ByteArrayInputStream(raw.toByteArray()), root.resolve("out").toFile());
      fail();
    } catch (IOException expected) {
    }
    assertFalse(Files.exists(root.resolve("escaped")));
  }

  @Test
  public void boundedRead() throws Exception {
    try {
      FileEngine.bounded(new ByteArrayInputStream(new byte[10]), 9);
      fail();
    } catch (IOException expected) {
    }
  }

  @Test
  public void refusesOverwrite() throws Exception {
    Path root = Files.createTempDirectory("ice-existing");
    File src = root.resolve("src").toFile();
    Files.write(src.toPath(), "hello".getBytes());
    File target = root.resolve("x.zip").toFile();
    Files.write(target.toPath(), "original".getBytes());
    try {
      FileEngine.zip(src, target);
      fail();
    } catch (IOException expected) {
    }
    assertEquals("original", new String(Files.readAllBytes(target.toPath())));
  }
}
