package com.shilapi.xcertplay.riding

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.os.PowerManager
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowPowerManager
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29,33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RideForegroundServiceTest {
    private fun drain() {
        RideRuntime.io.submit {}.get(10,TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun screenOffRecordingAndNotificationControlsSurviveServiceRecreation() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        val store=RideStore.get(app)
        val id=store.start("test-device",1,"outbound",System.currentTimeMillis()-1000)
        val controller=Robolectric.buildService(RideForegroundService::class.java).create()
        val service=controller.get()
        val manager=app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            service.onStartCommand(null,0,1);drain()
            val lock=ShadowPowerManager.getLatestWakeLock()
            assertTrue(lock.isHeld)
            shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
            service.onLocationChanged(Location("gps").apply {
                time=System.currentTimeMillis();latitude=22.0;longitude=113.0;accuracy=4f;speed=2f
            });drain()
            assertEquals(1L,store.active()!!.count)
            var notice=shadowOf(manager).getNotification(133)
            assertEquals(Notification.VISIBILITY_PUBLIC,notice.visibility)
            assertEquals(service.getString(R.string.riding_pause),notice.actions[0].title.toString())
            service.onStartCommand(shadowOf(notice.actions[0].actionIntent).savedIntent,0,2);drain()
            assertEquals("PAUSED",store.active()!!.state);assertFalse(lock.isHeld)
            notice=shadowOf(manager).getNotification(133)
            assertEquals(service.getString(R.string.riding_paused),notice.extras.getString(Notification.EXTRA_TEXT))
            assertEquals(service.getString(R.string.riding_resume),notice.actions[0].title.toString())
            // Sticky recreation restores the saved paused state without resuming it.
            service.onStartCommand(null,0,3);drain()
            assertEquals("PAUSED",store.active()!!.state)
            service.onStartCommand(shadowOf(notice.actions[0].actionIntent).savedIntent,0,4);drain()
            assertEquals("RECORDING",store.active()!!.state);assertTrue(lock.isHeld)
            controller.destroy();assertFalse(lock.isHeld)
            val restored=Robolectric.buildService(RideForegroundService::class.java).create()
            try {
                restored.get().onStartCommand(null,0,5);drain()
                assertEquals(id,store.active()!!.id)
                notice=shadowOf(manager).getNotification(133)
                assertEquals(RideForegroundService.FINISH,shadowOf(notice.actions[1].actionIntent).savedIntent.action)
                restored.get().onStartCommand(shadowOf(notice.actions[1].actionIntent).savedIntent,0,6);drain()
                assertNull(store.active());assertEquals(1L,store.latest()!!.count)
            } finally {restored.destroy()}
        } finally {
            if(store.active()!=null) {store.finish(System.currentTimeMillis());controller.destroy()}
            store.close()
            RideStore::class.java.getDeclaredField("instance").apply {isAccessible=true}.set(null,null)
        }
    }
}
