/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.View;
import androidx.media3.common.C;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.BeforeClass;
import org.junit.Test;

public final class High10SurfaceRecreationTest {
  @BeforeClass public static void loadFixture() throws Exception {
    High10NativeLifecycleTest.loadFixture();
  }

  @Test public void pausedStyleRecreationPreservesPixelsAndReusesJavaSurface() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Intent intent = new Intent(instrumentation.getContext(), High10SurfaceTestActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    High10SurfaceTestActivity activity =
        (High10SurfaceTestActivity) instrumentation.startActivitySync(intent);
    try (High10NativeLifecycleTest.Session session = new High10NativeLifecycleTest.Session()) {
      assertTrue(activity.created.await(5, TimeUnit.SECONDS));
      // Callback fires inside updateSurface; complete UI transaction before producer geometry.
      instrumentation.runOnMainSync(() -> {});
      Surface surface = activity.view.getHolder().getSurface();
      VideoDecoderOutputBuffer held = High10NativeLifecycleTest.firstOutput(session);
      try {
        session.decoder.renderToSurface(held, surface);
        Bitmap before = copySurface(surface, held.width, held.height);
        try {
          assertEquals("Limited-range fixture black must map to RGB black", 0,
              before.getPixel(0, 0) & 0xffffff);
          assertEquals("Limited-range fixture white must map to RGB white", 0xffffff,
              before.getPixel(before.getWidth() - 1, 0) & 0xffffff);
          boolean variedPixels = false;
          int firstPixel = before.getPixel(0, 0);
          for (int y = 0; y < before.getHeight(); y += 32) {
            for (int x = 0; x < before.getWidth(); x += 32) {
              variedPixels |= before.getPixel(x, y) != firstPixel;
            }
          }
          assertTrue("Readback contains fixture, not empty/black Surface", variedPixels);
          instrumentation.runOnMainSync(() -> activity.view.setVisibility(View.GONE));
          assertTrue(activity.destroyed.await(5, TimeUnit.SECONDS));
          activity.created = new CountDownLatch(1);
          instrumentation.runOnMainSync(() -> activity.view.setVisibility(View.VISIBLE));
          assertTrue(activity.created.await(5, TimeUnit.SECONDS));
          instrumentation.runOnMainSync(() -> {});
          assertSame(surface, activity.view.getHolder().getSurface());
          session.decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
          // Submit same held frame; no next frame hides a broken paused-style restoration.
          session.decoder.renderToSurface(held, surface);
          Bitmap after = copySurface(surface, held.width, held.height);
          try {
            if (!before.sameAs(after)) {
              File directory = instrumentation.getContext().getCacheDir();
              save(before, new File(directory, "surface-before.png"));
              save(after, new File(directory, "surface-after.png"));
            }
            assertTrue("Recreated Surface differs from same decoded frame", before.sameAs(after));
            assertEquals(2, session.decoder.getPerformanceSnapshot(0, 0, 0, 0).stage("surface_post").count);
          } finally { after.recycle(); }
        } finally { before.recycle(); }
      } finally { held.release(); }
    } finally { instrumentation.runOnMainSync(activity::finish); }
  }

  private static Bitmap copySurface(Surface surface, int width, int height) throws Exception {
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    CountDownLatch done = new CountDownLatch(1);
    int[] status = {-1};
    PixelCopy.request(surface, result, code -> {status[0] = code; done.countDown();},
        new Handler(Looper.getMainLooper()));
    assertTrue("Surface readback timed out", done.await(5, TimeUnit.SECONDS));
    assertEquals("Surface readback failed", PixelCopy.SUCCESS, status[0]);
    return result; // Buffer readback, not proof of physical HDMI presentation.
  }

  private static void save(Bitmap bitmap, File file) throws Exception {
    try (FileOutputStream output = new FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
    }
  }
}
