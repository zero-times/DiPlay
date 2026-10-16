# Optional offline cycling

This module is for an Android phone mounted on a bicycle. It records independently of CarPlay and is available in **Advanced → Experimental display → Cycling · Devices and rides** and the side panel. Use the existing side-panel size setting for a one-third riding panel.

## Install a complete build

For cycling alongside standalone CarPlay, build `:mobile:assembleStandaloneDebug` with the runtime authentication input described in [Build from source](BUILD.md#build-a-standalone-car-test-apk). Install `mobile/build/outputs/apk/standalone/mobile-standalone-debug.apk`. The ordinary source-only CI APK lacks CarPlay authentication and cannot make an independent iPhone connection.

The standalone debug build uses the same application ID and debug signing key as the source debug build. Install it over that build to preserve settings and local rides. A release app has a different application ID and signing key; it is not an in-place update for the debug app.

## Bind once, record offline

1. While online, open Cycling and tap **Refresh binding / sync**.
2. In the 百宝格 mini-program, open **我的骑行 → 骑行设备**. Scan the QR code shown by DiPlay or enter its eight-digit code. Codes expire after ten minutes; refresh for a new one.
3. Wait for **Device bound** on DiPlay. Allow precise location when starting the first ride.
4. Choose outbound or return. Start, pause, resume and finish work without internet. Points are committed to the local database before upload; finished rides remain in Local rides.
5. After connectivity returns, DiPlay verifies the device binding and uploads in batches, resuming from the server's acknowledged sequence. The mini-program shows the completed ride in its existing history and route view. An overlapping ride from another source requires attention instead of creating duplicate earnings.

The optional service uses `https://wechat.aiwanwang.cn`. Binding requires a mini-program account and an existing cycling goal. The binding QR code is scanned **inside** the mini-program; it is not a WeChat launch code.

## Screen off and lock-screen controls

Start a ride while DiPlay is visible. Recording then runs in a location foreground service independently of the Activity or CarPlay connection. A partial CPU wake lock is held only during recording; it is released on pause, finish and service destruction. Its timeout is renewed while recording without keeping the display on.

Allow Android notification permission and lock-screen notifications. The ongoing Cycling notification offers **Pause / Resume** and **Finish ride**, including on the lock screen where the device's security settings permit it. The notification displays the recording state, without route coordinates. App initialization does not stop the recorder, and a service recreation restores the persisted recording or paused state.

Some manufacturers restrict background GPS even for foreground services. **Background recording settings** opens Android's battery settings so the user can allow DiPlay to run in the background. Exact controls vary by firmware. Force stop, permission revocation, reboot and restrictive firmware can interrupt recording; no app can guarantee continued operation in those conditions. Open Cycling to recover the locally saved active session after an interruption. GPS gaps remain gaps, rather than invented route segments.

## Storage and privacy

Raw coordinates, timestamps and local statistics stay in the app's no-backup SQLite database until synchronization and are retained locally afterward. Pairing proof and device credentials are stored in no-backup storage encrypted with an Android Keystore AES-GCM key. They are excluded from Android backup. Upload uses HTTPS and a device credential; no WeChat session is stored in DiPlay.

The account owner can rename or revoke a device in the mini-program. A device that was offline learns about revocation at the next verification. Unsynced rides from a revoked binding remain locally quarantined and are never reassigned to a new account. Uninstalling DiPlay removes the local database and credentials; sync needed rides first.

## Validation scope (2026-10-16)

Automated checks cover 7,200 simulated one-second samples persisted and reopened, pauses, bad GPS fixes, durable contiguous acknowledgments, lost batch/finish responses and idempotent retry. Service tests on Android API 29 and 33 cover screen-off samples, notification pause/resume/finish, wake-lock release, and recovery of recording/paused sessions. Large-font compact, full and Arabic panel screenshots are generated under `common/build/reports/riding-ui/`.

These simulations do not establish physical GPS, battery life, CarPlay interoperability or manufacturer lock-screen behavior. Device acceptance still requires a two-hour offline ride, screen-off controls, CarPlay disconnect/reconnect, process recovery, and later synchronization to the mini-program on the target phone.
