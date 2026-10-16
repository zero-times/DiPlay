package com.shilapi.xcertplay.riding

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import com.shilapi.xcertplay.host.R

/** Its lifetime is unrelated to a CarPlay session, Activity, or side panel. */
class RideForegroundService: Service(), LocationListener {
    private val locationThread=HandlerThread("riding-location")
    private val main=Handler(Looper.getMainLooper())
    private lateinit var manager: LocationManager
    private var listening=false
    private var destroyed=false
    private lateinit var wakeLock: PowerManager.WakeLock
    private val renewWakeLock=object:Runnable {
        override fun run() {
            if(destroyed) return
            wakeLock.acquire(WAKE_TIMEOUT_MS)
            main.postDelayed(this,WAKE_RENEW_MS)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate();locationThread.start();manager=getSystemService(LocationManager::class.java)
        wakeLock=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"DiPlay:riding").apply {setReferenceCounted(false)}
    }
    override fun onStartCommand(intent: Intent?,flags: Int,startId: Int): Int {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {stopSelf();return START_NOT_STICKY}
        foreground()
        RideRuntime.io.execute {
            val store=RideStore.get(this)
            try {
                when(intent?.action) {
                    START_OUTBOUND,START_RETURN -> {
                        val binding=RideRuntime.binding(this).read()
                        if(binding.optString("binding_state")=="BOUND" && binding.optString("device_credential").isNotEmpty())
                            store.start(binding.getString("device_id"),binding.getInt("binding_version"),if(intent.action==START_RETURN) "return" else "outbound",System.currentTimeMillis())
                    }
                    PAUSE -> store.pause(System.currentTimeMillis())
                    RESUME -> store.resume(System.currentTimeMillis())
                    FINISH -> {store.finish(System.currentTimeMillis());RideRuntime.schedule(this)}
                }
                val active=store.active()
                main.post { if(destroyed) return@post; if(active==null) stopSelf() else {foreground(active.state=="PAUSED");listen(active.state=="RECORDING")} }
            } catch(_:Exception) { main.post { stopSelf() } }
        }
        return START_STICKY
    }
    private fun foreground(paused:Boolean=false) {
        val channel="diplay_riding"
        if(Build.VERSION.SDK_INT>=26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,getString(R.string.riding_title),NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,0,Intent(this,RidingActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        fun action(command: String,request: Int):PendingIntent {
            val intent=Intent(this,RideForegroundService::class.java).setAction(command)
            val flags=PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            return if(Build.VERSION.SDK_INT>=26) PendingIntent.getForegroundService(this,request,intent,flags)
                else PendingIntent.getService(this,request,intent,flags)
        }
        val builder=if(Build.VERSION.SDK_INT>=26) Notification.Builder(this,channel) else @Suppress("DEPRECATION") Notification.Builder(this)
        if(Build.VERSION.SDK_INT>=31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        val notification=builder.setSmallIcon(R.drawable.ic_diplay_notification).setContentTitle(getString(R.string.riding_title))
            .setContentText(getString(if(paused) R.string.riding_paused else R.string.riding_recording)).setContentIntent(open).setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC).setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true).setShowWhen(false)
            .addAction(Notification.Action.Builder(null,getString(if(paused) R.string.riding_resume else R.string.riding_pause),action(if(paused) RESUME else PAUSE,1)).build())
            .addAction(Notification.Action.Builder(null,getString(R.string.riding_finish),action(FINISH,2)).build()).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(133,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(133,notification)
    }
    @Suppress("MissingPermission")
    private fun listen(recording: Boolean) {
        main.removeCallbacks(renewWakeLock)
        if(!recording) {
            manager.removeUpdates(this);listening=false
            if(wakeLock.isHeld) wakeLock.release()
            return
        }
        // Only hold the CPU awake during recording; the display may remain off.
        renewWakeLock.run()
        if(listening) return
        try {
            val providers=manager.allProviders
            val primary=when { LocationManager.GPS_PROVIDER in providers -> LocationManager.GPS_PROVIDER; "fused" in providers -> "fused"; else -> LocationManager.NETWORK_PROVIDER }
            manager.requestLocationUpdates(primary,1000L,0f,this,locationThread.looper)
            if(primary!=LocationManager.NETWORK_PROVIDER && LocationManager.NETWORK_PROVIDER in providers)
                manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,1000L,0f,this,locationThread.looper)
            listening=true
        } catch(_:SecurityException) {stopSelf()} catch(_:IllegalArgumentException) {listening=false}
    }
    override fun onLocationChanged(location: Location) {
        val now=System.currentTimeMillis()
        if(location.time>now+120000 || now-location.time>10000) return
        val point=RidePoint(location.time,location.latitude,location.longitude,
            if(location.hasAccuracy()) location.accuracy.toDouble() else null,
            if(location.hasSpeed()) location.speed.toDouble() else null,
            if(location.hasAltitude()) location.altitude else null,
            if(location.hasBearing()) location.bearing.toDouble() else null,location.provider)
        RideRuntime.io.execute { try {RideStore.get(this).append(point)} catch(_:Exception) { main.post {stopSelf()} } }
    }
    @Deprecated("Required for Android 7") override fun onStatusChanged(provider: String?,status: Int,extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit
    override fun onDestroy() {
        destroyed=true
        main.removeCallbacksAndMessages(null)
        if(wakeLock.isHeld) wakeLock.release()
        manager.removeUpdates(this);locationThread.quitSafely();super.onDestroy()
    }
    companion object {
        private const val WAKE_TIMEOUT_MS=10*60*1000L
        private const val WAKE_RENEW_MS=5*60*1000L
        const val START_OUTBOUND="riding.START_OUTBOUND"; const val START_RETURN="riding.START_RETURN"
        const val PAUSE="riding.PAUSE";const val RESUME="riding.RESUME";const val FINISH="riding.FINISH"
    }
}
