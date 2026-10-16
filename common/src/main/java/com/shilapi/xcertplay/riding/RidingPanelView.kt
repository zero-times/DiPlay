package com.shilapi.xcertplay.riding

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.util.TypedValue
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Scrollable at large font sizes and narrow panel widths; controls always retain 48dp touch targets. */
class RidingPanelView(private val context: Context): ScrollView(context) {
    private val handler=Handler(android.os.Looper.getMainLooper())
    private val content=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(16),dp(16),dp(16),dp(24))}
    private val status=text(16f)
    private val speed=text(64f)
    private val unit=text(14f).apply {text="km/h"}
    private val distance=text(24f)
    private val clock=text(22f)
    private val average=text(14f)
    private val gps=text(14f)
    private val sync=text(14f)
    private val controls=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
    private var loading=false
    private var attached=false
    private var controlsKey=""
    private val tick=object:Runnable {override fun run() {refresh();if(attached) handler.postDelayed(this,1000)}}
    init {
        setBackgroundColor(themeColor(android.R.attr.colorBackground))
        isFillViewport=true;addView(content,LayoutParams(-1,-2))
        content.addView(status);content.addView(speed);content.addView(unit);content.addView(distance);content.addView(clock);content.addView(average);content.addView(gps);content.addView(sync);content.addView(controls,LinearLayout.LayoutParams(-1,-2))
    }
    private fun text(size:Float)=TextView(context).apply {textSize=size;gravity=Gravity.CENTER;setPadding(0,dp(4),0,dp(4));setTextColor(themeColor(android.R.attr.textColorPrimary))}
    private fun themeColor(attr:Int):Int {val value=TypedValue();context.theme.resolveAttribute(attr,value,true);return if(value.resourceId!=0) ContextCompat.getColor(context,value.resourceId) else value.data}
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    override fun onAttachedToWindow() {super.onAttachedToWindow();attached=true;handler.post(tick)}
    override fun onDetachedFromWindow() {attached=false;handler.removeCallbacks(tick);super.onDetachedFromWindow()}
    fun render(ride:RideSession?,bound:Boolean,now:Long=System.currentTimeMillis(),fix:org.json.JSONObject?=null) {
        val active=ride?.active==true
        status.setText(when { !bound -> R.string.riding_bind_required;ride?.state=="PAUSED" -> R.string.riding_paused;active -> R.string.riding_recording;ride!=null -> R.string.riding_saved;else -> R.string.riding_ready })
        speed.text=String.format(Locale.getDefault(),"%.1f",if(active && ride?.state=="RECORDING") ride.speed*3.6 else 0.0)
        distance.text=String.format(Locale.getDefault(),"%.2f km",(ride?.distance ?: 0.0)/1000)
        val seconds=(ride?.movingMs(now) ?: 0)/1000
        clock.text=String.format(Locale.getDefault(),"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
        average.text=context.getString(R.string.riding_average, String.format(Locale.getDefault(),"%.1f",(ride?.average(now) ?: 0.0)*3.6),String.format(Locale.getDefault(),"%.1f",(ride?.maxSpeed ?: 0.0)*3.6))
        gps.setText(when {
            !active || ride?.state=="PAUSED" -> R.string.riding_local
            fix==null || now-fix.optLong("timestamp")>10000 -> R.string.riding_gps_wait
            fix.optString("quality")!="GOOD" -> R.string.riding_gps_weak
            else -> R.string.riding_gps_good
        })
        sync.text=RideSyncStatus.text(context,ride)
        val key="$bound:${ride?.state}"
        if(controlsKey!=key) {
            controlsKey=key;controls.removeAllViews()
            when {
                active -> {button(if(ride?.state=="PAUSED") R.string.riding_resume else R.string.riding_pause) {command(if(ride?.state=="PAUSED") RideForegroundService.RESUME else RideForegroundService.PAUSE)};button(R.string.riding_finish) {command(RideForegroundService.FINISH)}}
                bound -> {button(R.string.riding_start_outbound) {command(RideForegroundService.START_OUTBOUND)};button(R.string.riding_start_return) {command(RideForegroundService.START_RETURN)}}
            }
            button(R.string.riding_manage) {context.startActivity(Intent(context,RidingActivity::class.java))}
        }
    }
    private fun button(label:Int,action:()->Unit) {controls.addView(Button(context).apply {setText(label);minimumHeight=dp(48);setOnClickListener{action()}},LinearLayout.LayoutParams(-1,-2))}
    private fun command(action:String) {
        if(context is RidingActivity) {context.command(action);return}
        if(context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {context.startActivity(Intent(context,RidingActivity::class.java));return}
        ContextCompat.startForegroundService(context,Intent(context,RideForegroundService::class.java).setAction(action))
    }
    private fun refresh() {
        if(loading || !attached) return
        loading=true
        RideRuntime.io.execute {
            var ride:RideSession?=null;var bound=false;var fix:org.json.JSONObject?=null
            try {ride=RideStore.get(context).latest();fix=ride?.let {RideStore.get(context).lastPoint(it.id)};bound=RideRuntime.binding(context).read().optString("binding_state")=="BOUND"} catch(_:Exception) { }
            handler.post {loading=false;if(attached) render(ride,bound,fix=fix)}
        }
    }
}
