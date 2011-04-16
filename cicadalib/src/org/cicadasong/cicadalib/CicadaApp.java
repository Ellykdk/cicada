// Copyright (C) 2011 Cicada contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.package org.cicadasong.cicadalib;

package org.cicadasong.cicadalib;

import org.cicadasong.apollo.ApolloConfig;
import org.cicadasong.apollo.BitmapUtil;
import org.cicadasong.cicadalib.CicadaIntents.ButtonEvent;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.IBinder;

/**
 * Base class for Cicada apps that provides a clean interface for dealing with CicadaService.
 */

public abstract class CicadaApp extends Service {
  
  public enum AppType {
    NONE,
    WIDGET,
    APP,
    WIDGET_AND_APP
  }
  
  private AppType currentMode = AppType.NONE;
  private Bitmap bitmap;
  private Canvas canvas;
  private boolean isActive = false;
  private BroadcastReceiver receiver;
  private int sessionId;

  @Override
  public void onCreate() {
    receiver = new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        handleIntent(intent);
      }
    };
    
    IntentFilter filter = new IntentFilter();
    filter.addAction(CicadaIntents.INTENT_ACTIVATE_APP);
    filter.addAction(CicadaIntents.INTENT_DEACTIVATE_APP);
    filter.addAction(CicadaIntents.INTENT_BUTTON_EVENT);
    registerReceiver(receiver, filter);
    
    super.onCreate();
  }

  @Override
  public void onDestroy() {
    deactivate();
    unregisterReceiver(receiver);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
  
  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    handleIntent(intent);
    return super.onStartCommand(intent, flags, startId);
  }

  /**
   * @return the human-readable name of the app (only used for debugging at the moment)
   */
  public String getAppName() {
    return this.getClass().getSimpleName();
  }

  /**
   * Called when this app is activated by CicadaService.  onDraw() will be triggered at the
   * conclusion of this method.
   * 
   * @param mode the mode that the app is being activated in
   */
  protected abstract void onActivate(AppType mode);
  
  /**
   * Called when this app is deactivated; it should no longer call invalidate() to trigger screen
   * updates past this point.
   */
  protected abstract void onDeactivate();
  
  /**
   * Called when one or more device buttons are pressed.
   * 
   * @param buttonEvent indicates the buttons that were pressed.
   */
  protected abstract void onButtonPress(ButtonEvent buttonEvent);
  
  /**
   * Called when the app needs to render its screen contents.  This method should not be called
   * directly by the app; if the app author wishes to trigger a screen update, they should call
   * invalidate().
   * 
   * @param canvas the canvas on which the display contents will be drawn.  Note that the canvas
   *        will be different sizes in widget and app modes, and that the contents of the canvas
   *        will be flattened to monochrome before being pushed to the display.
   */
  protected abstract void onDraw(Canvas canvas);
  
  protected AppType getCurrentMode() {
    return currentMode;
  }
  
  private void handleIntent(Intent intent) {
    if (intent.getAction().equals(CicadaIntents.INTENT_ACTIVATE_APP)) {
      AppType mode = AppType.APP;
      String modeString = intent.getStringExtra(CicadaIntents.EXTRA_APP_MODE);
      sessionId = intent.getIntExtra(CicadaIntents.EXTRA_SESSION_ID, 0);
      if (modeString != null) {
        mode = AppType.valueOf(modeString);
      }
      activate(mode);
    } else if (intent.getAction().equals(CicadaIntents.INTENT_DEACTIVATE_APP)) {
      CicadaApp.this.deactivate();
    } else if (intent.getAction().equals(CicadaIntents.INTENT_BUTTON_EVENT) && isActive) {
      ButtonEvent event = CicadaIntents.ButtonEvent.parseIntent(intent);
      if (event != null) {
        onButtonPress(event);
      }
    }
  }
  
  private Canvas getCanvas() {
    if (canvas == null) {
      int height = ApolloConfig.DISPLAY_HEIGHT;
      if (getCurrentMode() == AppType.WIDGET) {
        height /= 3;
      }
      bitmap = Bitmap.createBitmap(ApolloConfig.DISPLAY_WIDTH, height, Bitmap.Config.RGB_565);
      canvas = new Canvas(bitmap);
    }
    
    return canvas;
  }
  
  /**
   * Push the canvas to the device.  Before calling this, get the canvas via getCanvas() and
   * draw on it using Android graphics functions.  Note that the canvas contents will be flattened
   * to monochrome for display on the device.  This method should only be called when the app
   * is active.
   */
  private void pushCanvas() {
    if (!isActive || canvas == null) {
      return;
    }
    
    Intent intent = new Intent(CicadaIntents.INTENT_PUSH_CANVAS);
    intent.putExtra(CicadaIntents.EXTRA_BUFFER, BitmapUtil.bitmapToBuffer(bitmap));
    intent.putExtra(CicadaIntents.EXTRA_APP_NAME, getAppName());
    intent.putExtra(CicadaIntents.EXTRA_SESSION_ID, sessionId);
    sendBroadcast(intent);
  }
  
  /**
   * Called by the Cicada app to request a screen update.  The app's onDraw will subsequently
   * be called with an appropriately-sized canvas, and the result pushed to the device.
   */
  protected void invalidate() {
    if (!isActive) {
      throw new RuntimeException("CicadaApp.invalidate() called when the app wasn't active!");
    }
    
    // Make sure the canvas has been initialized to the appropriate size for the app mode
    getCanvas();
    
    // Ensure that the canvas is white, since the apps will tend to start drawing in black
    canvas.drawColor(Color.WHITE);
    
    onDraw(canvas);
    pushCanvas();
  }
  
  /**
   * Called by the Cicada app to trigger a vibration.  Should only be called when the app is active.
   * 
   * @param onMillis the duration in milliseconds of each pulse
   * @param offMillis the duration in milliseconds of the pauses between pulses
   * @param numPulses the number of pulses
   */
  protected void vibrate(int onMillis, int offMillis, int numPulses) {
    if (!isActive) {
      throw new RuntimeException("CicadaApp.vibrate() called when the app wasn't active!");
    }
    Intent intent = new Intent(CicadaIntents.INTENT_VIBRATE);
    intent.putExtra(CicadaIntents.EXTRA_VIBRATE_ON_MSEC, onMillis);
    intent.putExtra(CicadaIntents.EXTRA_VIBRATE_OFF_MSEC, offMillis);
    intent.putExtra(CicadaIntents.EXTRA_VIBRATE_NUM_CYCLES, numPulses);
    intent.putExtra(CicadaIntents.EXTRA_APP_NAME, getAppName());
    intent.putExtra(CicadaIntents.EXTRA_SESSION_ID, sessionId);
    sendBroadcast(intent);
  }
  
  protected boolean isActive() {
    return isActive;
  }
  
  private void activate(AppType mode) {
    isActive = true;
    if (currentMode != mode) {
      // Make sure the canvas is reset to the correct size when changing mode.
      canvas = null;
    }
    currentMode = mode;
    onActivate(mode);
    invalidate();
  }
  
  private void deactivate() {
    isActive = false;
    
    canvas = null;
    bitmap = null;
    
    onDeactivate();
  }
}
