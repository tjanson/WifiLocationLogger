package com.tomjanson.wifilocationlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;

import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/*
 * Receives Wifi scan result whenever WifiManager has them,
 * updates `wifiListString` and `lastWifiScanTime`,
 * logs location (and accuracy) and Wifis (SSID, BSSID, strength) to disk
 */
class WifiBroadcastReceiver extends BroadcastReceiver {
    private final MainActivity m;

    private final Comparator<ScanResult> RSSI_ORDER =
            new Comparator<ScanResult>() {
                public int compare(ScanResult e1, ScanResult e2) {
                    return Integer.compare(e2.level, e1.level);
                }
            };

    private static final String WIFI_SCAN_TIMER = "wifi-scan-timer";
    private static Timer wifiScanTimer;

    public WifiBroadcastReceiver(MainActivity m) {
        this.m = m;
        wifiScanTimer = new Timer(WIFI_SCAN_TIMER);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        List<ScanResult> scanResultList = MainActivity.wifiManager.getScanResults();
        m.lastWifiScanTime = new Date();

        Collections.sort(scanResultList, RSSI_ORDER);
        m.log.debug("Wifis: {}", scanResultList);

        String combined = "";
        Pattern filter = makeFilter();

        for (ScanResult wifi : scanResultList) {
            if (!filter.matcher(wifi.SSID).matches()) {
                continue;
            }

            combined += wifi.SSID + " [" + wifi.BSSID + "]" + ": " + wifi.level + "\n";
            log(wifi);
        }

        m.wifiListString = combined;
        m.updateUI();

        // schedule next scan after short delay
        wifiScanTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    MainActivity.wifiManager.startScan();
                }
            }, MainActivity.WIFI_SCAN_DELAY_MILLIS);
    }

    private void log(ScanResult wifi) {
        if (m.logToFile) {
            m.diskLog.info(m.currentLocation.getLatitude()
                 + ";" + m.currentLocation.getLongitude()
                 + ";" + m.currentLocation.getAltitude()
                 + ";" + m.currentLocation.getAccuracy()
                 + ";" + m.lastLocationUpdateTime.getTime()
                 + ";" + wifi.SSID
                 + ";" + wifi.BSSID
                 + ";" + wifi.level
                 + ";" + m.lastWifiScanTime.getTime());
        }
    }

    private Pattern makeFilter() {
        // if not a valid regular expression or empty, don't filter at all
        String regexp = m.wifiFilterET.getText().toString();
        if (regexp.equals("")) {
            regexp = ".*";
        }
        try {
            return Pattern.compile(regexp);
        } catch (PatternSyntaxException ex) {
            return Pattern.compile(".*");
        }
    }

}
