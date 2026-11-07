/* SPDX-License-Identifier: Apache-2.0 */
package com.nuvio.hi10video;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.util.concurrent.CountDownLatch;

/** Test-only real SurfaceHolder; holder retains same Java Surface across native recreation. */
public final class High10SurfaceTestActivity extends Activity implements SurfaceHolder.Callback {
  SurfaceView view;
  volatile CountDownLatch created = new CountDownLatch(1);
  final CountDownLatch destroyed = new CountDownLatch(1);

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    view = new SurfaceView(this);
    view.getHolder().addCallback(this);
    setContentView(view);
  }
  @Override public void surfaceCreated(SurfaceHolder holder) { created.countDown(); }
  @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}
  @Override public void surfaceDestroyed(SurfaceHolder holder) { destroyed.countDown(); }
}
