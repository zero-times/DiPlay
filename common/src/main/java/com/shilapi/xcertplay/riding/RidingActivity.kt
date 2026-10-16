package com.shilapi.xcertplay.riding

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.shilapi.xcertplay.host.R
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class RidingActivity: Activity() {
    private val main=Handler(Looper.getMainLooper())
    private lateinit var bindingText:TextView
    private lateinit var code:TextView
    private lateinit var qr:ImageView
    private lateinit var refresh:Button
    private lateinit var reset:Button
    private lateinit var history:LinearLayout
    private var pendingCommand:String?=null
    private var busy=false
    private var visible=false
    private var bound=false
    private val poll=object:Runnable {override fun run() {if(visible && !bound) loadBinding(false);if(visible) main.postDelayed(this,5000)}}
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(24))}
        val scroll=ScrollView(this).apply {isFillViewport=true;addView(root)};setContentView(scroll)
        root.setOnApplyWindowInsetsListener {view,insets -> view.setPadding(dp(20)+insets.systemWindowInsetLeft,dp(16)+insets.systemWindowInsetTop,dp(20)+insets.systemWindowInsetRight,dp(24)+insets.systemWindowInsetBottom);insets }
        root.addView(TextView(this).apply {setText(R.string.riding_title);textSize=28f})
        root.addView(Button(this).apply {setText(R.string.riding_back);minimumHeight=dp(48);setOnClickListener{finish()}})
        bindingText=TextView(this).apply {textSize=16f;setPadding(0,dp(16),0,dp(8))};root.addView(bindingText)
        code=TextView(this).apply {textSize=32f;gravity=Gravity.CENTER;setTextIsSelectable(true)};root.addView(code)
        qr=ImageView(this).apply {contentDescription=getString(R.string.riding_qr_description);adjustViewBounds=true};root.addView(qr,LinearLayout.LayoutParams(dp(216),dp(216)).apply{gravity=Gravity.CENTER_HORIZONTAL})
        refresh=Button(this).apply {setText(R.string.riding_refresh);minimumHeight=dp(48);setOnClickListener{loadBinding(true)}};root.addView(refresh)
        reset=Button(this).apply {setText(R.string.riding_rebind);minimumHeight=dp(48);visibility=android.view.View.GONE;setOnClickListener{resetBinding()}};root.addView(reset)
        root.addView(RidingPanelView(this),LinearLayout.LayoutParams(-1,dp(600)))
        root.addView(TextView(this).apply {setText(R.string.riding_background_help);textSize=14f;setPadding(0,dp(16),0,dp(8))})
        root.addView(Button(this).apply {
            setText(R.string.riding_background_settings);minimumHeight=dp(48)
            setOnClickListener {
                val settings=Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                if(settings.resolveActivity(packageManager)!=null) startActivity(settings)
                else startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:$packageName")))
            }
        })
        root.addView(TextView(this).apply {setText(R.string.riding_history);textSize=22f;setPadding(0,dp(24),0,dp(8))})
        history=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};root.addView(history)
        RideRuntime.initialize(this)
        RideRuntime.io.execute {val active=RideStore.get(this).active();main.post {if(active!=null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED) command(RideForegroundService.RESUME,restore=true)}}
    }
    override fun onStart() {super.onStart();visible=true;loadBinding(false);main.postDelayed(poll,5000);loadHistory()}
    override fun onStop() {visible=false;main.removeCallbacks(poll);super.onStop()}
    fun command(action:String,restore:Boolean=false) {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            if(restore) return
            pendingCommand=action
            AlertDialog.Builder(this).setTitle(R.string.riding_permission_title).setMessage(R.string.riding_permission_body)
                .setPositiveButton(R.string.riding_allow) {_,_->requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),133)}
                .setNegativeButton(android.R.string.cancel) {_,_->pendingCommand=null}.show();return
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED && !restore)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),134)
        val command=if(restore) null else action
        try {ContextCompat.startForegroundService(this,Intent(this,RideForegroundService::class.java).setAction(command))}
        catch(_:RuntimeException) {Toast.makeText(this,R.string.riding_service_error,Toast.LENGTH_LONG).show()}
        main.postDelayed({if(visible) loadHistory()},500)
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==133) {val action=pendingCommand;pendingCommand=null;if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED && action!=null) command(action) else Toast.makeText(this,R.string.riding_permission_body,Toast.LENGTH_LONG).show()}
    }
    private fun loadBinding(register:Boolean) {
        if(busy || !visible) return
        busy=true;refresh.isEnabled=false
        RideRuntime.network.execute {
            var snapshot=JSONObject();var message:Int?=null
            try {
                val binding=RideRuntime.binding(this);snapshot=binding.installation()
                if(snapshot.optString("binding_state")=="REVOKED") {message=R.string.riding_quarantined}
                else if(register || snapshot.optString("binding_state")=="PENDING") {
                    val api=RideApi()
                    if(register && snapshot.optString("binding_state")!="BOUND") {
                        val response=api.request("/api/v1/riding/devices/register",snapshot.getString("registration_secret"),JSONObject().put("installation_id",snapshot.getString("installation_id")))
                        merge(snapshot,response);binding.save(snapshot)
                    }
                    if(snapshot.has("device_id")) {
                        val response=if(snapshot.optString("binding_state")=="BOUND") api.request("/api/v1/riding/devices/${snapshot.getString("device_id")}/verify",snapshot.getString("device_credential"),JSONObject())
                            else api.request("/api/v1/riding/devices/${snapshot.getString("device_id")}/bind-status",snapshot.getString("registration_secret"))
                        merge(snapshot,response);binding.save(snapshot)
                        if(snapshot.optString("binding_state")=="BOUND") RideRuntime.schedule(this)
                    }
                }
            } catch(error:RideApiError) {
                if(error.code=="device_revoked" || error.status==401) {snapshot.put("binding_state","REVOKED");RideRuntime.binding(this).save(snapshot);RideStore.get(this).quarantine(snapshot.optString("device_id"));message=R.string.riding_quarantined}
                else message=R.string.riding_network_error
            } catch(_:Exception) {message=R.string.riding_network_error}
            main.post {
                busy=false;if(!visible)return@post
                refresh.isEnabled=true;bound=snapshot.optString("binding_state")=="BOUND"
                bindingText.setText(message ?: when {bound->R.string.riding_bound;snapshot.optString("binding_state")=="PENDING"->R.string.riding_scan_instruction;else->R.string.riding_bind_required})
                code.text=if(!bound && snapshot.optString("binding_state")=="PENDING") snapshot.optString("bind_code") else ""
                qr.visibility=if(code.text.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                reset.visibility=if(snapshot.optString("binding_state")=="REVOKED") android.view.View.VISIBLE else android.view.View.GONE
                if(qr.visibility==android.view.View.VISIBLE) qr.setImageBitmap(qrBitmap(snapshot.optString("qr_payload")))
                loadHistory()
            }
        }
    }
    private fun resetBinding() {
        AlertDialog.Builder(this).setTitle(R.string.riding_rebind).setMessage(R.string.riding_rebind_body).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.riding_rebind) {_,_->RideRuntime.io.execute {
                if(RideStore.get(this).active()!=null) {main.post{Toast.makeText(this,R.string.riding_finish_first,Toast.LENGTH_LONG).show()};return@execute}
                val binding=RideRuntime.binding(this);val old=binding.read();RideStore.get(this).quarantine(old.optString("device_id"));binding.save(JSONObject());main.post{bound=false;loadBinding(true)}
            }}.show()
    }
    private fun loadHistory() {
        RideRuntime.io.execute {
            val rides=RideStore.get(this).history()
            main.post {if(!visible)return@post;history.removeAllViews();rides.forEach {ride->
                val state=when(ride.syncState){"SYNCED"->R.string.riding_synced;"QUARANTINED"->R.string.riding_quarantined;"CONFLICT"->R.string.riding_sync_conflict;"SYNC_ERROR"->R.string.riding_sync_error;else->R.string.riding_pending}
                history.addView(TextView(this).apply {textSize=16f;setPadding(0,dp(12),0,dp(12));text=getString(R.string.riding_history_row,
                    DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(ride.started)),getString(if(ride.direction=="outbound")R.string.riding_outbound else R.string.riding_return),
                    String.format(Locale.getDefault(),"%.2f",ride.distance/1000),getString(state),ride.ack.toString(),ride.count.toString())})
            }}
        }
    }
    private fun qrBitmap(value:String):Bitmap? = try {
        val matrix=MultiFormatWriter().encode(value,BarcodeFormat.QR_CODE,512,512)
        Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888).apply {for(y in 0 until 512)for(x in 0 until 512)setPixel(x,y,if(matrix[x,y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)}
    } catch(_:Exception) {null}
    private fun merge(target:JSONObject,source:JSONObject) {source.keys().forEach {if(it!="ok")target.put(it,source.get(it))}}
    private fun dp(value:Int)=(value*resources.displayMetrics.density).toInt()
}
