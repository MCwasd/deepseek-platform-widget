package com.tiramisu.deepseekwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import java.util.concurrent.TimeUnit

class DeepSeekWidget : AppWidgetProvider() {
    companion object {
        const val PREFS_NAME = "deepseek_widget_prefs"
        const val KEY_API_KEY = "api_key"
        const val ACTION_REFRESH = "com.tiramisu.deepseekwidget.ACTION_REFRESH"
        private const val ACTION_MODEL_TOGGLE = "com.tiramisu.deepseekwidget.ACTION_MODEL_TOGGLE"
        const val UPDATE_INTERVAL_MINUTES = 30L
        const val DISPLAY_PREFS = "deepseek_widget_display"
        private const val KEY_CACHED = "cached_text"
        private const val KEY_CACHED_DATA = "cached_data"
        private const val KEY_MODEL_INDEX = "model_index" // 0=Flash 1=Flash Vision Exp 2=Pro
        private const val KEY_MODEL_PRO = "model_pro" // 旧版布尔开关，迁移用
        const val MODEL_COUNT = 3
        fun modelLabel(index: Int): String = when (index) {
            2 -> "Pro ›"
            1 -> "Vision Exp ›"
            else -> "Flash ›"
        }
        /** 读取下半区模型选择；旧版 model_pro 布尔值自动迁移为索引。 */
        fun getModelIndex(c: Context): Int {
            val p = prefs(c)
            if (p.contains(KEY_MODEL_INDEX)) return p.getInt(KEY_MODEL_INDEX, 0)
            val legacy = if (p.getBoolean(KEY_MODEL_PRO, false)) 2 else 0
            p.edit().putInt(KEY_MODEL_INDEX, legacy).remove(KEY_MODEL_PRO).apply()
            return legacy
        }
        fun setModelIndex(c: Context, index: Int) {
            prefs(c).edit().putInt(KEY_MODEL_INDEX, ((index % MODEL_COUNT) + MODEL_COUNT) % MODEL_COUNT).apply()
        }
        private const val KEY_TZ_MODE = "tz_mode" // 0=设备时区 1=北京时间 2=UTC
        private const val BLUE = 0xFF4D6BFE.toInt()
        private const val BLUE_DARK = 0xFF5C78E8.toInt()
        private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE)
        private fun encryptedPrefs(c: Context) = try { val mk=MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(); EncryptedSharedPreferences.create(c,PREFS_NAME,mk,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM) } catch (_:Exception){ null }
        fun getApiKey(c: Context): String? = try { encryptedPrefs(c)?.getString(KEY_API_KEY,null) } catch (_:Exception){null}
        fun setApiKey(c: Context, key:String) { try { encryptedPrefs(c)?.edit()?.putString(KEY_API_KEY,key)?.apply() } catch (_:Exception){} }
        fun getTzMode(c: Context): Int = try { encryptedPrefs(c)?.getInt(KEY_TZ_MODE, 0) ?: 0 } catch (_:Exception){ 0 }
        fun setTzMode(c: Context, mode: Int) { try { encryptedPrefs(c)?.edit()?.putInt(KEY_TZ_MODE, mode)?.apply() } catch (_:Exception){} }
        fun getUsageTimeZone(c: Context): java.util.TimeZone = when (getTzMode(c)) {
            1 -> java.util.TimeZone.getTimeZone("Asia/Shanghai")
            2 -> java.util.TimeZone.getTimeZone("UTC")
            else -> java.util.TimeZone.getDefault()
        }
        fun getTokenFromAccount(c:Context):String? = try { DeepSeekAccountManager(c).getValidToken() } catch (_:Exception){ null }
        fun getAuthToken(c:Context):String?=getTokenFromAccount(c)?:getApiKey(c)
        private fun refreshPi(c:Context)=PendingIntent.getBroadcast(c,0,Intent(c,DeepSeekWidget::class.java).apply{action=ACTION_REFRESH},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun modelPi(c:Context)=PendingIntent.getBroadcast(c,1,Intent(c,DeepSeekWidget::class.java).apply{action=ACTION_MODEL_TOGGLE},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun serialize(d:WidgetDisplayData)=listOf(d.balance,d.todayCost,d.monthlyCost,d.monthlyTokens,d.updatedAt,d.flashData.totalTokens,d.flashData.cacheHitRate,d.flashData.cost,d.flashData.requests,d.proData.totalTokens,d.proData.cacheHitRate,d.proData.cost,d.proData.requests,d.error?:"",d.flashData.monthlyTokens,d.proData.monthlyTokens,d.visionData.totalTokens,d.visionData.cacheHitRate,d.visionData.cost,d.visionData.requests,d.visionData.monthlyTokens).joinToString("|")
        private fun deserialize(raw:String?):WidgetDisplayData? {
            return try {
                val p = raw?.split("|") ?: return null
                if (p.size < 14) return null
                WidgetDisplayData(true, p[0], todayCost = p[1], monthlyCost = p[2], monthlyTokens = p[3].toLong(), updatedAt = p[4].toLong(),
                    flashData = ModelData(p[5].toLong(), p[6], p[7], p[8].toLong(), p.getOrNull(14)?.toLongOrNull() ?: 0L),
                    proData = ModelData(p[9].toLong(), p[10], p[11], p[12].toLong(), p.getOrNull(15)?.toLongOrNull() ?: 0L),
                    visionData = ModelData(p.getOrNull(16)?.toLongOrNull() ?: 0L, p.getOrNull(17) ?: "--", p.getOrNull(18) ?: "0.00", p.getOrNull(19)?.toLongOrNull() ?: 0L, p.getOrNull(20)?.toLongOrNull() ?: 0L),
                    error = p[13].ifBlank { null })
            } catch (_: Exception) { null }
        }
        private fun render(v:RemoteViews,d:WidgetDisplayData,display:String,index:Int,status:String="") { v.setTextViewText(R.id.tv_title,"DeepSeek");v.setTextViewText(R.id.tv_updated,if(d.updatedAt>0)d.formattedUpdatedTime else "--:--");v.setTextViewText(R.id.tv_refresh_status,status);v.setTextViewText(R.id.tv_balance_value,display);v.setTextViewText(R.id.tv_today_value,d.formattedTodayCost);v.setTextViewText(R.id.tv_month_value,d.formattedMonthCost);v.setTextColor(R.id.tv_title,BLUE);v.setTextColor(R.id.tv_balance_value,BLUE_DARK);v.setTextColor(R.id.tv_today_value,BLUE_DARK);v.setTextColor(R.id.tv_month_value,BLUE_DARK);val m=d.modelData(index);v.setTextViewText(R.id.tv_model,modelLabel(index));v.setTextViewText(R.id.tv_tokens,WidgetDisplayData.formatTokenCount(m.totalTokens));v.setTextViewText(R.id.tv_requests,m.requests.toString());v.setTextViewText(R.id.tv_month_tokens,WidgetDisplayData.formatTokenCount(m.monthlyTokens));v.setTextViewText(R.id.tv_cache,if(m.cacheHitRate=="--")"--" else "${m.cacheHitRate}%");v.setTextViewText(R.id.tv_model_cost,"¥${m.cost}");v.setTextColor(R.id.tv_model_cost,0xFFD39B2C.toInt());val x=m.cacheHitRate.toDoubleOrNull();v.setTextColor(R.id.tv_cache,when{ x==null->0xFF687386.toInt();x>=90->0xFF198754.toInt();x>=80->0xFFE09F00.toInt();else->0xFFD93025.toInt()}) }
        fun updateWidgets(c:Context,d:WidgetDisplayData){val p=prefs(c);val display=if(d.error!=null)"⚠️ ${d.error}" else d.formattedBalance;p.edit().putString(KEY_CACHED,display).putString(KEY_CACHED_DATA,serialize(d)).apply();val idx=getModelIndex(c);val mgr=AppWidgetManager.getInstance(c);for(id in mgr.getAppWidgetIds(ComponentName(c,DeepSeekWidget::class.java))){val v=RemoteViews(c.packageName,R.layout.widget_layout);render(v,d,display,idx,"");v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)}}
        fun triggerRefresh(c:Context){val mgr=AppWidgetManager.getInstance(c);val p=prefs(c);val d=deserialize(p.getString(KEY_CACHED_DATA,null));for(id in mgr.getAppWidgetIds(ComponentName(c,DeepSeekWidget::class.java))){val v=RemoteViews(c.packageName,R.layout.widget_layout);if(d!=null)render(v,d,p.getString(KEY_CACHED,d.formattedBalance)?:d.formattedBalance,getModelIndex(c),"刷新中");v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)};WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
    }
    override fun onUpdate(c:Context,mgr:AppWidgetManager,ids:IntArray){schedulePeriodicUpdate(c);val p=prefs(c);for(id in ids){val v=RemoteViews(c.packageName,R.layout.widget_layout);val d=deserialize(p.getString(KEY_CACHED_DATA,null));if(d!=null)render(v,d,p.getString(KEY_CACHED,d.formattedBalance)?:d.formattedBalance,getModelIndex(c)) else v.setTextViewText(R.id.tv_balance_value,"⟳ 刷新中...");v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)};if(getAuthToken(c)!=null)triggerRefresh(c)}
    override fun onReceive(c:Context,i:Intent){super.onReceive(c,i);if(i.action==ACTION_MODEL_TOGGLE){setModelIndex(c,getModelIndex(c)+1);deserialize(prefs(c).getString(KEY_CACHED_DATA,null))?.let{updateWidgets(c,it)};return};if(i.action==ACTION_REFRESH&&!getAuthToken(c).isNullOrBlank())triggerRefresh(c)}
    override fun onEnabled(c:Context){super.onEnabled(c);schedulePeriodicUpdate(c)}
    override fun onDisabled(c:Context){super.onDisabled(c);WorkManager.getInstance(c).cancelUniqueWork(WIDGET_UPDATE_WORK_NAME)}
    private fun schedulePeriodicUpdate(c:Context){WorkManager.getInstance(c).enqueueUniquePeriodicWork(WIDGET_UPDATE_WORK_NAME,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<WidgetUpdateWorker>(UPDATE_INTERVAL_MINUTES,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
}
const val WIDGET_UPDATE_WORK_NAME="deepseek_widget_update"
