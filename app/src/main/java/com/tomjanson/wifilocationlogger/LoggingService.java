package com.tomjanson.wifilocationlogger;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import ch.qos.logback.classic.LoggerContext;

public class LoggingService extends Service implements
        ConnectionCallbacks, OnConnectionFailedListener, LocationListener {

    public static boolean serviceRunning = false;

    Callbacks client;
    private final IBinder binder = new MyBinder();

    // Logback loggers, see https://github.com/tony19/logback-android
    Logger log;       // regular log/debug messages
    Logger dataLog;   // sensor data (geo, wifi) for debugging (verbose)
    Logger diskLog;   // pretty CSV output, i.e., the "product" of this app

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
    private final static String LOGGING_ENABLED_KEY                  = "logging-enabled-key";

    // Used to access Fused Location API,
    // see https://developer.android.com/google/play-services/location.html
    private GoogleApiClient googleApiClient;
    private LocationRequest locationRequest;
    Location currentLocation;

    // Wifi scan stuff
    static WifiManager wifiManager;
    private static IntentFilter wifiIntentFilter;
    private static WifiBroadcastReceiver wifiBroadcastReceiver;

    // toggles logging location+wifi to file
    // debug logs may be created regardless of this
    boolean loggingEnabled = false;

    // wake-lock to (hopefully) continue logging while screen is off
    private PowerManager.WakeLock wakeLock;

    Date lastLocationUpdateTime;
    public String   wifiListString;
    Date     lastWifiScanTime;

    String wifiFilter;

    private int notificationId = 1;

    @Override
    public void onCreate() {
        serviceRunning = true;

        log = LoggerFactory.getLogger(LoggingService.class);
        dataLog = LoggerFactory.getLogger("data");
        diskLog = LoggerFactory.getLogger("disk");
        log.info("Started LoggingService; " + MainActivity.APP_VERSION + ", " + Build.VERSION.RELEASE + ", " + Build.ID + ", " + Build.MODEL);

        // run service in foreground foreground
        Notification notification = new Notification.Builder(this)
                .setContentTitle("Freifunk Logging")
                .setContentText("Logging is running")
                //TODO set icon
                //.setSmallIcon()
                .build();
        startForeground(notificationId, notification);





    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        wifiFilter = intent.getStringExtra(MainActivity.SSID_FILTER_KEY);

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakelockTag");
        wakeLock.acquire();
        buildGoogleApiClient();

        initWifiScan();
        wifiManager.startScan();
        googleApiClient.connect();
        log.trace("Connecting GoogleApiClient ...");
        //Tell Android to restart this service if it was killed by Android.
        return START_REDELIVER_INTENT;
    }

    @Override
    public void onDestroy() {
        log.trace("onDestroy");
        serviceRunning = false;

        disconnectGoogleApiClient();

        this.unregisterReceiver(wifiBroadcastReceiver);
        log.trace("Unregistered WifiBroadcastReceiver");

        // assume SLF4J is bound to logback-classic in the current environment
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        loggerContext.stop();

        wakeLock.release();
    }

    private void disconnectGoogleApiClient() {
        if (googleApiClient.isConnected()) {
            googleApiClient.disconnect();
            log.trace("GoogleApiClient disconnected");
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        log.trace("MainActivity was bound to LoggingService");
        return binder;

    }

    // Stop this service
    public void stopService(){
        serviceRunning = false;
        stopSelf();
    }

    public void registerClient(Callbacks client){
        this.client = client;
    }

    public void unregisterClient(){
        this.client = null;
    }

    public void updateClients(){
        if(client != null){
            client.updateClient();
        }
    }

    public void onWarn(){
        if(client != null){
            client.onWarn();
        }
    }


    private void initWifiScan() {
        log.trace("initWifi");
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiBroadcastReceiver = new WifiBroadcastReceiver(this);
        wifiIntentFilter = new IntentFilter();
        wifiIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        this.registerReceiver(wifiBroadcastReceiver, wifiIntentFilter);
        log.debug("Registered WifiBroadcastReceiver");
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
            onWarn();
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        dataLog.trace("Location: {}", location);
        currentLocation = location;
        lastLocationUpdateTime = new Date();
        //Tell client activity to update itself
        updateClients();
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
            //Tell client activity to update itself
            updateClients();
        }

        startLocationUpdates();
    }

    @Override
    public void onConnectionFailed(ConnectionResult result) {
        log.warn("GoogleApiClient connection failed: ConnectionResult.getErrorCode() = {}", result.getErrorCode());
        onWarn();
    }

    @Override
    public void onConnectionSuspended(int cause) {
        log.info("GoogleApiClient connection suspended, attempting reconnect");
        googleApiClient.connect();
    }
    public class MyBinder extends Binder{
        public LoggingService getServiceInstance(){
            return LoggingService.this;
        }
    }

    // Client activity must implement this interface. Service will call client.updateClient() when client should update itself.
    public interface Callbacks{
        public void updateClient();
        public void onWarn();
    }
}