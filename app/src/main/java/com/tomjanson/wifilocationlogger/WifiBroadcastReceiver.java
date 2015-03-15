package com.tomjanson.wifilocationlogger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.ScanResult;
import android.os.Build;

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

    private static final int NOT_SPECIAL = 0;
    private static final int SPECIAL_NO_VISIBLE_WIFI = 1;

    public WifiBroadcastReceiver(MainActivity m) {
        this.m = m;
        wifiScanTimer = new Timer(WIFI_SCAN_TIMER);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        List<ScanResult> scanResultList = MainActivity.wifiManager.getScanResults();
        m.lastWifiScanTime = new Date();

        Collections.sort(scanResultList, RSSI_ORDER);
        m.dataLog.trace("Wifis: {}", scanResultList);

        String combined = "";
        Pattern filter = makeFilter();

        Boolean atLeastOneLogged = false;
        for (ScanResult wifi : scanResultList) {
            if (!filter.matcher(wifi.SSID).matches()) {
                continue;
            }
            atLeastOneLogged = true;

            combined += convertFrequencyToChannel(wifi.frequency) + " " + wifi.SSID + " [" + wifi.BSSID + "]" + ": " + wifi.level + "\n";
            log(wifi, NOT_SPECIAL);
        }

        // if no log entry was triggered (i.e., no wifis that matched the filter),
        // write special entry signifying that no wifi was in range
        if (!atLeastOneLogged) {
           log(null, SPECIAL_NO_VISIBLE_WIFI);
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

    private void log(ScanResult wifi, int specialCode) {
        if (m.loggingEnabled) {
            String csvLine =     MainActivity.LOG_FORMAT_VERSION
                         + "," + Build.MODEL
                         + "," + MainActivity.sessionId
                         + "," + m.currentLocation.getLatitude()
                         + "," + m.currentLocation.getLongitude()
                         + "," + m.currentLocation.getAltitude()
                         + "," + m.currentLocation.getAccuracy()
                         + "," + m.currentLocation.getSpeed()
                         + "," + specialCode
                         + "," + (m.lastLocationUpdateTime.getTime() - m.lastWifiScanTime.getTime());

            if (specialCode == NOT_SPECIAL) {
                csvLine += "," + wifi.SSID // FIXME: escape commas
                         + "," + wifi.BSSID
                         + "," + wifi.level
                         + "," + convertFrequencyToChannel(wifi.frequency);
            } else if (specialCode == SPECIAL_NO_VISIBLE_WIFI) {
                csvLine += ",,,,";
            } else {
                throw new IllegalArgumentException(Integer.toString(specialCode));
            }

            // FIXME last in case it might contain messed up characters
            // this really needs to be escaped properly, same for SSID above
            // (which can apparently contain arbitrary characters - dear god...)
            csvLine +=     "," + "'" + m.wifiFilterET.getText().toString() + "'";

            m.diskLog.info(csvLine);
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

    private static int convertFrequencyToChannel(int freq) {
        if (freq >= 2412 && freq <= 2484) {
            return (freq - 2412) / 5 + 1;
        } else if (freq >= 5170 && freq <= 5825) {
            return (freq - 5170) / 5 + 34;
        } else {
            throw new IllegalArgumentException(Integer.toString(freq));
        }
    }

}
