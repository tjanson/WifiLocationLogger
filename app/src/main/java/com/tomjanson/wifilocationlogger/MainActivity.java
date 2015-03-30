package com.tomjanson.wifilocationlogger;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DateFormat;
import java.util.UUID;

import ch.qos.logback.classic.LoggerContext;

/*
 * Location update code based on:
 *     https://developer.android.com/training/location/receive-location-updates.html
 * Wifi scan code loosely based on:
 *     http://www.tutorialspoint.com/android/android_wi_fi.htm
 *     https://github.com/Skarbo/WifiMapper
 */

public class MainActivity extends Activity implements LoggingService.Callbacks{

    // Logback loggers, see https://github.com/tony19/logback-android
    Logger log;       // regular log/debug messages

    // anti-spam filter on server
    static final String UPLOAD_SECRET = "sLlx6PaL";

    // unique ID sent to server to distinguish clients
    // changes every time logging is enabled
    static String sessionId;

    // UI Elements
    Button   loggingButton;
    EditText uploadUrlET;
    TextView locationTV;
    TextView locationAccuracyTV;
    TextView locationUpdateTV;
    TextView wifiTV;
    EditText wifiFilterET;
    TextView wifiUpdateTV;
    //String   wifiListString;

    // keys for saving user preferences
    private final static String SSID_FILTER_PREFERENCE_KEY = "ssid-filter-preference-key";
    private final static String UPLOAD_URL_PREFERENCE_KEY  = "upload-url-preference-key";

    public final static String SSID_FILTER_KEY = "ssid-filter-key";

    // will be incremented when log format changes
    final static int LOG_FORMAT_VERSION = 1;

    final static int APP_VERSION = 1;

    private LoggingService loggingService;

    private ServiceConnection mConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // After MainActivity was bound to LoggingService, get the LoggingService object
            LoggingService.MyBinder binder = (LoggingService.MyBinder) service;
            loggingService = binder.getServiceInstance();
            loggingService.registerClient((LoggingService.Callbacks) MainActivity.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            loggingService.unregisterClient();
            loggingService = null;
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log = LoggerFactory.getLogger(MainActivity.class);
        log.info("Started; " + APP_VERSION + ", " + Build.VERSION.RELEASE + ", " + Build.ID + ", " + Build.MODEL);

        setContentView(R.layout.activity_main);

        assignUiElements();

        // restore saved preferences
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        wifiFilterET.setText(sharedPref.getString(SSID_FILTER_PREFERENCE_KEY, getString(R.string.ssid_filter_default)));
        uploadUrlET.setText(sharedPref.getString(UPLOAD_URL_PREFERENCE_KEY, getString(R.string.upload_url_default)));

    }

    private void assignUiElements() {
        loggingButton       = (Button)   findViewById(R.id.loggingButton);
        uploadUrlET         = (EditText) findViewById(R.id.uploadServerUrlEditText);
        locationTV          = (TextView) findViewById(R.id.locationTextView);
        locationAccuracyTV  = (TextView) findViewById(R.id.locationAccuracyTextView);
        locationUpdateTV    = (TextView) findViewById(R.id.locationUpdateTextView);
        wifiTV              = (TextView) findViewById(R.id.wifiTextView);
        wifiFilterET        = (EditText) findViewById(R.id.wifiFilterEditText);
        wifiUpdateTV        = (TextView) findViewById(R.id.wifiUpdateTextView);
    }

    void updateUI() {
        if (loggingService != null) {
            if (loggingService.currentLocation != null) {
                locationTV.setText(loggingService.currentLocation.getLatitude() + ", " + loggingService.currentLocation.getLongitude());
                locationAccuracyTV.setText(loggingService.currentLocation.getAccuracy() + " m");
            }
            if (loggingService.lastLocationUpdateTime != null) {
                locationUpdateTV.setText(DateFormat.getTimeInstance().format(loggingService.lastLocationUpdateTime));
            }
            if (loggingService.lastWifiScanTime != null) {
                wifiUpdateTV.setText(DateFormat.getTimeInstance().format(loggingService.lastWifiScanTime));
            }
            if (loggingService.wifiListString != null) {
                wifiTV.setText(loggingService.wifiListString);
            }

            loggingButton.setText(LoggingService.serviceRunning ? R.string.logging_stop : R.string.logging_start);
        }
    }

    /**
     * Toggles logging data points to file and aquires wake-lock to do so while screen is off.
     */
    public void toggleLogging(View view) {

        if (!LoggingService.serviceRunning) {
            sessionId = UUID.randomUUID().toString();
            log.info("SessionID for remote logging: " + sessionId);

            // start service and bind to it.
            log.trace("Start LoggingService and bind to it");
            Intent serviceIntent = new Intent(MainActivity.this, LoggingService.class);
            serviceIntent = new Intent(MainActivity.this, LoggingService.class);
            serviceIntent.putExtra(SSID_FILTER_KEY, wifiFilterET.getText().toString());
            startService(serviceIntent);
            bindService(serviceIntent, mConnection, 0);
        } else {
            // stop service
            log.trace("Stop LoggingService");
            loggingService.stopService();
        }
        log.info((LoggingService.serviceRunning ? "En" : "Dis") + "abled logging to disk");
        updateClient();
    }




    @Override
    protected void onStart() {
        super.onStart();
        log.trace("onStart");

        // bind to service, if it is running
        if (LoggingService.serviceRunning){
            Intent serviceIntent = new Intent(MainActivity.this, LoggingService.class);
            log.trace("Bind to LoggingService");
            bindService(serviceIntent, mConnection, 0);
            updateClient();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        log.trace("onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        log.trace("onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        log.trace("onStop");

        //unbind service
        log.trace("Unbind from LoggingService");
        loggingService.unregisterClient();
        loggingService = null;
        unbindService(mConnection);

        // save preferences or other persisting stuff
        SharedPreferences.Editor prefEditor = this.getPreferences(Context.MODE_PRIVATE).edit();
        prefEditor.putString(SSID_FILTER_PREFERENCE_KEY, wifiFilterET.getText().toString());
        prefEditor.putString(UPLOAD_URL_PREFERENCE_KEY, uploadUrlET.getText().toString());
        prefEditor.apply();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log.trace("onDestroy");

        // assume SLF4J is bound to logback-classic in the current environment
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.stop();
    }


    /**
     * Clears focus from EditText (the SSID filter).
     * This is needed because EditText grabs focus and opens the keyboard, which is annoying.
     * See GridLayout's focussing attributes, which prevents ET focus grab at startup.
     *
     * https://stackoverflow.com/questions/1555109/stop-edittext-from-gaining-focus-at-activity-startup/
     */
    public void removeFocusFromEditText(View view) {
        wifiFilterET.clearFocus();
        uploadUrlET.clearFocus();
    }

    public void triggerUpload(View view) {
        Uploader.upload(this, "/sdcard/WifiLocationLogger/wifilog.csv");
    }

    @Override
    public void updateClient() {
        updateUI();
    }

    public void onWarn() {
        try {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            Ringtone r = RingtoneManager.getRingtone(getApplicationContext(), notification);
            r.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
        new AlertDialog.Builder(this)
            .setTitle(getString(R.string.warning))
            .setMessage(getString(R.string.warning_msg_logged))
            .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                }
            })
            .setIcon(android.R.drawable.ic_dialog_alert)
            .show();
    }
}
