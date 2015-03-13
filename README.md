# WifiLocationLogger

Logs current location and signal strength of visible wireless networks. (Log location: `/sdcard/WifiLocationLogger`.) [APK Download](https://github.com/tjanson/WifiLocationLogger/releases)

This application is *experimental* – do not expect it to work well, or at all. Tested with Android 5.0.2.

Feel free to open an Issue for bug reports or feedback of any kind.
Pull requests very welcome.

The output format is bound to change often, so please look at [the relevant section of the source code](https://github.com/tjanson/WifiLocationLogger/blob/master/app/src/main/java/com/tomjanson/wifilocationlogger/WifiBroadcastReceiver.java#L72-L84) to figure out the fields’ meanings.

![screenshot](https://raw.githubusercontent.com/tjanson/WifiLocationLogger/master/other/screenshot.small.png)

## To-Do

- [x] collect real-world data on a variety of devices and see whether it's usable at all
- [x] make the app work while in the background [[possible improvements](https://github.com/tjanson/WifiLocationLogger/issues/1)]
- [x] visualize data points on map
- [ ] explore alternative visualizations, improve Hexbin visualization [to be published]
- [ ] [lots of stuff regarding remote logging](https://github.com/tjanson/WifiLocationLogger/issues/5) plus DB backend
- [ ] document tools for CSV handling and conversion
