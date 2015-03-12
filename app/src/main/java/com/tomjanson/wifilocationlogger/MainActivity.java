package com.tomjanson.wifilocationlogger;

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import android.net.wifi.WifiManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.text.DateFormat;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/*
 * Location update code based on:
 *     https://developer.android.com/training/location/receive-location-updates.html
 * Wifi scan code loosely based on:
 *     http://www.tutorialspoint.com/android/android_wi_fi.htm
 *     https://github.com/Skarbo/WifiMapper
 */

public class MainActivity extends Activity implements
        ConnectionCallbacks, OnConnectionFailedListener, LocationListener {

    // Logback loggers, see https://github.com/tony19/logback-android
    Logger log;     // regular log/debug messages
    Logger dataLog; // sensor data (geo, wifi) for debugging (verbose)
    Logger diskLog; // pretty CSV output, i.e., the "product" of this app

    // Location update intervals
    // it seems the updates take at least 5s; setting it lower doesn't seem to work
    static final long LOCATION_UPDATE_INTERVAL_MILLIS = 3000;
    static final long FASTEST_LOCATION_UPDATE_INTERVAL_MILLIS = 1000;

    // Wifi scan delay (i.e., wait $delay between completion of scan and start of next scan)
    static final long WIFI_SCAN_DELAY_MILLIS = 2000;

    // TODO: try different delays

    // Instance state Bundle keys,
    // see https://developer.android.com/training/basics/activity-lifecycle/recreating.html
    private final static String LOCATION_KEY                         = "location-key";
    private final static String LAST_LOCATION_UPDATE_TIME_STRING_KEY = "last-location-update-time-string-key";
    private final static String LAST_WIFI_SCAN_TIME_STRING_KEY       = "last-wifi-scan-time-string-key";

    // Used to access Fused Location API,
    // see https://developer.android.com/google/play-services/location.html
    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    Location currentLocation;

    // Wifi scan stuff
    static WifiManager           wifiManager;
    static IntentFilter          wifiIntentFilter;
    static WifiBroadcastReceiver wifiBroadcastReceiver;

    // toggles logging location+wifi to file
    // debug logs may be created regardless of this
    boolean logToFile = false;

    // UI Elements
    Button   loggingButton;
    TextView locationTV;
    TextView locationAccuracyTV;
    TextView locationUpdateTV;
    Date     lastLocationUpdateTime;
    TextView wifiTV;
    EditText wifiFilterET;
    TextView wifiUpdateTV;
    String   wifiListString;
    Date     lastWifiScanTime;

    private final static String SSID_FILTER_PREFERENCE_KEY = "ssid-filter-preference-key";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        log = LoggerFactory.getLogger(MainActivity.class);
        dataLog = LoggerFactory.getLogger("data");
        diskLog = LoggerFactory.getLogger("disk");
        log.info("Started; " + Build.VERSION.RELEASE + ", " + Build.ID + ", " + Build.MODEL);

        setContentView(R.layout.activity_main);

        assignUiElements();

        // restore saved preferences
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        wifiFilterET.setText(sharedPref.getString(SSID_FILTER_PREFERENCE_KEY, getString(R.string.ssid_filter_default)));

        // restore state on Activity recreation
        updateValuesFromBundle(savedInstanceState);

        buildGoogleApiClient();

        initWifiScan();
        wifiManager.startScan();
    }

    private void assignUiElements() {
        loggingButton       = (Button)   findViewById(R.id.loggingButton);
        locationTV          = (TextView) findViewById(R.id.locationTextView);
        locationAccuracyTV  = (TextView) findViewById(R.id.locationAccuracyTextView);
        locationUpdateTV    = (TextView) findViewById(R.id.locationUpdateTextView);
        wifiTV              = (TextView) findViewById(R.id.wifiTextView);
        wifiFilterET        = (EditText) findViewById(R.id.wifiFilterEditText);
        wifiUpdateTV        = (TextView) findViewById(R.id.wifiUpdateTextView);
    }

    void updateUI() {
        if (currentLocation != null) {
            locationTV.setText(currentLocation.getLatitude() + ", " + currentLocation.getLongitude());
            locationAccuracyTV.setText(currentLocation.getAccuracy() + " m");
        }
        if (lastLocationUpdateTime != null) {
            locationUpdateTV.setText(DateFormat.getTimeInstance().format(lastLocationUpdateTime));
        }
        if (lastWifiScanTime != null) {
            wifiUpdateTV.setText(DateFormat.getTimeInstance().format(lastWifiScanTime));
        }
        if (wifiListString != null) {
            wifiTV.setText(wifiListString);
        }

        if (logToFile) {
            loggingButton.setText(R.string.logging_stop);
        } else {
            loggingButton.setText(R.string.logging_start);
        }
    }

    public void toggleLogging(View view) {
        logToFile = !logToFile;
        updateUI();
    }

    private void initWifiScan() {
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiBroadcastReceiver = new WifiBroadcastReceiver(this);
        wifiIntentFilter = new IntentFilter();
        wifiIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        this.registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);
        log.debug("Registered WifiBroadcastReceiver");
    }

    private void updateValuesFromBundle(Bundle savedInstanceState) {
        log.debug("Updating values from Bundle");
        if (savedInstanceState != null) {
            if (savedInstanceState.keySet().contains(LOCATION_KEY)) {
                currentLocation = savedInstanceState.getParcelable(LOCATION_KEY);
            }
            if (savedInstanceState.keySet().contains(LAST_LOCATION_UPDATE_TIME_STRING_KEY)) {
                lastLocationUpdateTime = new Date(savedInstanceState.getLong(LAST_LOCATION_UPDATE_TIME_STRING_KEY));
            }
            if (savedInstanceState.keySet().contains(LAST_WIFI_SCAN_TIME_STRING_KEY)) {
                lastWifiScanTime = new Date(savedInstanceState.getLong(LAST_WIFI_SCAN_TIME_STRING_KEY));
            }
            updateUI();
        }
    }

    synchronized void buildGoogleApiClient() {
        log.trace("Building GoogleApiClient");
        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
        createLocationRequest();
    }

    void createLocationRequest() {
        locationRequest = new LocationRequest();

        // Sets the desired interval for active location updates. This interval is
        // inexact. You may not receive updates at all if no location sources are available, or
        // you may receive them slower than requested. You may also receive updates faster than
        // requested if other applications are requesting location at a faster interval.
        locationRequest.setInterval(LOCATION_UPDATE_INTERVAL_MILLIS);

        // Sets the fastest rate for active location updates. This interval is exact, and your
        // application will never receive updates faster than this value.
        locationRequest.setFastestInterval(FASTEST_LOCATION_UPDATE_INTERVAL_MILLIS);

        locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    /**
     * Requests location updates from the FusedLocationApi.
     */
    void startLocationUpdates() {
        // The final argument to {@code requestLocationUpdates()} is a LocationListener
        // (http://developer.android.com/reference/com/google/android/gms/location/LocationListener.html).
        LocationServices.FusedLocationApi.requestLocationUpdates(googleApiClient, locationRequest, this);
        log.trace("Requesting GoogleApiClient location updates");
    }

    void stopLocationUpdates() {
        if (googleApiClient.isConnected()) {
            LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
            log.trace("Stopped location updates");
        } else {
            log.warn("Attempted to stop location updates, but not connected");
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        dataLog.trace("Location: {}", location);
        currentLocation = location;
        lastLocationUpdateTime = new Date();
        updateUI();
    }

    @Override
    protected void onStart() {
        super.onStart();
        googleApiClient.connect();
        log.trace("Connecting GoogleApiClient ...");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (googleApiClient.isConnected()) {
            startLocationUpdates();
        }
        this.registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);
        log.debug("Registered wifiBroadcastReceiver");
        wifiManager.startScan();
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.unregisterReceiver(wifiBroadcastReceiver);
        log.debug("Unregistered wifiBroadcastReceiver");
        if (googleApiClient.isConnected()) {
            stopLocationUpdates();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }

        // save preferences or other persisting stuff
        SharedPreferences.Editor prefEditor = this.getPreferences(Context.MODE_PRIVATE).edit();
        prefEditor.putString(SSID_FILTER_PREFERENCE_KEY, wifiFilterET.getText().toString());
        prefEditor.apply();
    }

    /**
     * Runs when a GoogleApiClient object successfully connects.
     */
    @Override
    public void onConnected(Bundle connectionHint) {
        log.info("Connected to GoogleApiClient");

        if (currentLocation == null) {
            currentLocation = LocationServices.FusedLocationApi.getLastLocation(googleApiClient);
            lastLocationUpdateTime = new Date();
            updateUI();
        }

        startLocationUpdates();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        log.warn("GoogleApiClient connection failed: ConnectionResult.getErrorCode() = {}", result.getErrorCode());
    }

    @Override
    public void onConnectionSuspended(int cause) {
        log.info("GoogleApiClient connection suspended, attempting reconnect");
        googleApiClient.connect();
    }
    /**
     * Stores activity data in the Bundle.
     */
    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        savedInstanceState.putParcelable(LOCATION_KEY, currentLocation);
        if (lastLocationUpdateTime != null) {
            savedInstanceState.putLong(LAST_LOCATION_UPDATE_TIME_STRING_KEY, lastLocationUpdateTime.getTime());
        }
        if (lastWifiScanTime != null) {
            savedInstanceState.putLong(LAST_WIFI_SCAN_TIME_STRING_KEY, lastWifiScanTime.getTime());
        }
        super.onSaveInstanceState(savedInstanceState);
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
    }
}
