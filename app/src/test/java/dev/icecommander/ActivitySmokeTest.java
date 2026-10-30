package dev.icecommander;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ActivitySmokeTest {
  @Test
  public void homeAndDualPanelsSurviveRecreation() {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      assertNotNull(activity.root);
      activity.dual = true;
      activity.prefs.edit().putBoolean("dual", true).commit();
      activity.show();
      controller.recreate();
      assertTrue(controller.get().dual);
      assertEquals("", controller.get().paths[0]);
      assertEquals("", controller.get().paths[1]);
    }
  }

  @Test
  public void customColorsAreRestored() {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      controller.get().setThemeColors(0xff122334, 0xff66ddff);
      controller.recreate();
      assertEquals(0xff122334, controller.get().bg);
      assertEquals(0xff66ddff, controller.get().accent);
    }
  }

  @Test
  public void fileOperationsHandleQuotedNames() throws Exception {
    Access.mode = "normal";
    java.nio.file.Path root = java.nio.file.Files.createTempDirectory("ice-files");
    String folder = root.resolve("new folder").toString();
    Access.operation("mkdir", "", folder);
    String original = new java.io.File(folder, "a'b.txt").getPath();
    Access.operation("create", "", original);
    assertEquals(1, Access.list(folder, true).size());
    String copied = root.resolve("copy.txt").toString();
    Access.operation("copy", original, copied);
    assertTrue(new java.io.File(original).exists());
    String moved = root.resolve("moved.txt").toString();
    Access.operation("move", copied, moved);
    assertFalse(new java.io.File(copied).exists());
    assertTrue(new java.io.File(moved).exists());
    Access.operation("delete", folder, "");
    assertFalse(new java.io.File(folder).exists());
  }

  @Test
  public void textEditorLoadsAndSavesFile() throws Exception {
    Access.mode = "normal";
    java.nio.file.Path file = java.nio.file.Files.createTempFile("ice-editor", ".txt");
    java.nio.file.Files.write(file, "before".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    android.content.Intent intent =
        new android.content.Intent(
                org.robolectric.RuntimeEnvironment.getApplication(), ViewerActivity.class)
            .putExtra("path", file.toString());
    try (ActivityController<ViewerActivity> controller =
        Robolectric.buildActivity(ViewerActivity.class, intent).setup()) {
      ViewerActivity activity = controller.get();
      activity.worker.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertEquals("before", activity.editor.getText().toString());
      activity.editor.setText("after");
      activity.save();
      activity.worker.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
      org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
      assertEquals(
          "after",
          new String(
              java.nio.file.Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
      assertFalse(activity.dirty);
    }
  }
}
