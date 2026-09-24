# Network Monitor — v1

This Android Studio project extends Stage 1 with:
```- Native Android network information through a Kotlin ↔ JavaScript bridge
- Wi-Fi RSSI/dBm
- Wi-Fi link speed
- Wi-Fi frequency when exposed by Android
- Wi-Fi/mobile/ethernet connection type
- Download speed test
- Upload speed test
- Existing latency/jitter/packet-loss monitoring
- Live latency chart```

## Build
Open the project in Android Studio, sync Gradle, connect a device, and Run.

## Notes
1. Wi-Fi RSSI access can require location permission on Android. The app requests ACCESS_FINE_LOCATION at startup.
2. Speed tests use Cloudflare's public speed-test endpoints. A speed result is a measurement to that endpoint, not a guaranteed ISP maximum.
3. The upload/download test sizes are intentionally moderate to avoid excessive data use.
4. Stage 2 is still a foreground diagnostic app. Background monitoring, history persistence, heatmaps and richer native diagnostics can be added later.
