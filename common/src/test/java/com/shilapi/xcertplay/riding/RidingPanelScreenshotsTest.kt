package com.shilapi.xcertplay.riding

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RidingPanelScreenshotsTest {
    @Test fun narrowFullAndRtlPanelsRemainScrollableAtLargeFontScale() {
        val activity=Robolectric.buildActivity(Activity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        val directory=File("build/reports/riding-ui").apply {mkdirs()}
        for((name,widthDp,language) in listOf(Triple("compact-zh",280,"zh-CN"),Triple("full-en",800,"en-US"),Triple("compact-ar",280,"ar-SA"),Triple("compact-sync-error-zh",280,"zh-CN"),Triple("compact-synced-no-gps-zh",280,"zh-CN"))) {
            val locale=Locale.forLanguageTag(language)
            val config=Configuration(activity.resources.configuration).apply {fontScale=1.5f;setLocale(locale);setLayoutDirection(locale)}
            @Suppress("DEPRECATION") activity.resources.updateConfiguration(config,activity.resources.displayMetrics)
            val view=RidingPanelView(activity)
            var ride=RideSession("id","device",1,"outbound",0,null,"RECORDING",12820.0,0,null,2000,1500,8.1,7.3,"PENDING","")
            if(name=="compact-sync-error-zh") ride=ride.copy(state="FINISHED_LOCAL",finished=2058000,distance=0.0,count=0,ack=0,syncState="CONFLICT",error="invalid_finish")
            if(name=="compact-synced-no-gps-zh") ride=ride.copy(state="FINISHED_LOCAL",finished=2058000,distance=0.0,count=0,ack=0,syncState="SYNCED")
            view.render(ride,true,2058000)
            val width=(widthDp*activity.resources.displayMetrics.density).toInt();val height=(640*activity.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height)
            assertTrue(view.getChildAt(0).measuredHeight>0)
            val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image));File(directory,"$name.png").outputStream().use {assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it))};image.recycle()
        }
    }
}
