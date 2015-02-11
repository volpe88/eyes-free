package com.google.marvin.paw;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;

// This activity is fairly stupid - it just sends the button presses to the
// Service.

public class WidgetGlueActivity extends Activity {
  @Override
  protected void onResume() {
    super.onResume();
    //Log.e("PAW", "WidgetGlueActivity called with " + getIntent().getAction());
    Intent serviceIntent = new Intent("com.google.marvin.paw.runWidget");
    serviceIntent.putExtra("input", getIntent().getAction());
    this.startService(serviceIntent);
    finish();
  }
}
