package com.tiramisu.deepseekwidget

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** 宽容解析平台返回的数值字段：可能是 "123"、"123.0"，也兼容 JSON number 被字符串化的情况。 */
private fun String?.toLongLoose(): Long = this?.toDoubleOrNull()?.toLong() ?: 0L

/**
 * DeepSeek open-platform API client.
 *
 * Uses the CURRENT platform endpoints (2026-08):
 *  - summary:        GET /api/v0/users/get_user_summary        (balance / available tokens)
 *  - usage amounts:  GET /api/v0/usage/by_api_key/amount       ?start=<epochSec>&end=<epochSec>&tz=<offsetSec>
 *  - usage costs:    GET /api/v0/usage/by_api_key/cost         ?start=<epochSec>&end=<epochSec>&tz=<offsetSec>
 *
 * The old endpoints `/api/v0/usage/amount?month=&year=` and `/api/v0/usage/cost?month=&year=`
 * were replaced by the platform (the web console now groups usage by API key and supports a
 * configurable billing/display timezone). Ranges are expressed in Unix epoch seconds and the
 * `tz` parameter carries the timezone offset in seconds (multiple of 900, -43200..50400),
 * matching the official web client.
 *
 * The timezone used for "today" / "this month" boundaries is passed in via constructor
 * (device timezone by default, or the user-selected override from the widget config).
 */
class DeepSeekApiClient(
    private val token: String,
    private val usageTimeZone: TimeZone = TimeZone.getDefault()
) {

    companion object {
        private const val PLATFORM_BASE = "https://platform.deepseek.com"
        private const val SUMMARY_URL = "$PLATFORM_BASE/api/v0/users/get_user_summary"
        private const val AMOUNT_URL = "$PLATFORM_BASE/api/v0/usage/by_api_key/amount"
        private const val COST_URL = "$PLATFORM_BASE/api/v0/usage/by_api_key/cost"
        private const val TIMEOUT_SECONDS = 15L

        // 模型名归一化（2026-10）：V4.1 起平台把旧模型名合并
        //   deepseek-v4-flash / deepseek-v4-flash-vision-exp / deepseek-chat ... → deepseek-flash
        // 因此不再按“完整旧名”精确匹配，改为按关键字归一到展示桶；
        // 未识别的名称进 OTHER 桶（只计入今日/本月总额，避免漏账）。
        private const val BUCKET_FLASH = "flash"
        private const val BUCKET_VISION = "vision"
        private const val BUCKET_PRO = "pro"
        private const val BUCKET_OTHER = "other"
    }

    private val gson = Gson()
    private val tzOffsetMinutes = usageTimeZone.getOffset(System.currentTimeMillis()) / 60000

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .header("x-client-platform", "web")
        .header("x-client-version", "1.0.0")
        .header("x-app-version", "1.0.0")
        .header("x-client-timezone-offset", tzOffsetMinutes.toString())
        .build()

    fun fetchAll(): WidgetDisplayData {
        try {
            val summary = fetchSummary()
            val month = fetchMonthUsage()
            Log.d("DS_WIDGET", "summary=$summary, monthFlash=${month.flash}, monthPro=${month.pro}, todayTotal=${month.todayCostTotal}")
            return WidgetDisplayData(
                isAvailable = true,
                balance = summary.balance,
                totalAvailableTokens = summary.totalAvailableTokens,
                todayCost = month.todayCostTotal,
                monthlyCost = month.monthCostTotal,
                monthlyTokens = month.monthTokens,
                flashData = month.flash,
                visionData = month.vision,
                proData = month.pro,
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("DS_API", "fetchAll failed", e)
            return WidgetDisplayData(error = e.message ?: "未知错误")
        }
    }

    // ─── get_user_summary ──────────────────────────────────────

    private data class SummaryResult(
        val balance: String = "0.00",
        val totalAvailableTokens: Long = 0
    )

    private fun fetchSummary(): SummaryResult {
        val body = execute(SUMMARY_URL)
        val resp = gson.fromJson(body, SummaryResponse::class.java)
        val biz = resp.data?.bizData ?: throw Exception("summary 数据为空")

        val balance = biz.normalWallets?.firstOrNull()?.let {
            "%.2f".format(it.balance?.toDoubleOrNull() ?: 0.0)
        } ?: "0.00"

        val totalAvailableTokens = biz.totalAvailableTokenEstimation?.toLongOrNull() ?: 0L

        return SummaryResult(balance, totalAvailableTokens)
    }

    // ─── 本月 + 今日用量（新 by_api_key 端点，时区感知）──────────

    private data class TokenBreakdown(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheHitTokens: Long = 0,
        val cacheMissTokens: Long = 0,
        val requests: Long = 0
    ) {
        // 新端点只返回 HIT/MISS/RESPONSE/REQUEST；输入 = 命中 + 未命中
        val totalTokens: Long get() = inputTokens + outputTokens
        val cacheHitRate: String get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total > 0)
                "%.1f".format((cacheHitTokens.toDouble() / total) * 100)
            else "--"
        }

        fun add(u: UsageByKeyUsage): TokenBreakdown {
            val hit = u.cacheHitToken.toLongLoose()
            val miss = u.cacheMissToken.toLongLoose()
            val prompt = u.promptToken.toLongLoose()
            // 新端点输入 = HIT + MISS；若返回 PROMPT_TOKEN 但无细分，则用其作输入
            val input = if (hit + miss > 0) hit + miss else prompt
            return TokenBreakdown(
                inputTokens = this.inputTokens + input,
                outputTokens = this.outputTokens + u.responseToken.toLongLoose(),
                cacheHitTokens = this.cacheHitTokens + hit,
                cacheMissTokens = this.cacheMissTokens + miss,
                requests = this.requests + u.request.toLongLoose()
            )
        }
    }

    private data class MonthUsageResult(
        val todayCostTotal: String = "0.00",
        val monthCostTotal: String = "0.00",
        val monthTokens: Long = 0,
        val flash: ModelData = ModelData(),
        val vision: ModelData = ModelData(),
        val pro: ModelData = ModelData()
    )

    /** 把平台返回的模型名归一到展示桶，兼容 V4.1 的模型名合并与后续任何改名。 */
    private fun bucketOf(model: String?): String {
        val m = model?.lowercase() ?: return BUCKET_OTHER
        return when {
            m.contains("pro") -> BUCKET_PRO
            m.contains("vision") -> BUCKET_VISION
            m.contains("flash") || m.contains("chat") || m.contains("reason") -> BUCKET_FLASH
            else -> BUCKET_OTHER
        }
    }

    private fun fetchMonthUsage(): MonthUsageResult {
        val now = Calendar.getInstance(usageTimeZone)
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        val tzOffsetSec = usageTimeZone.getOffset(System.currentTimeMillis()) / 1000
        val monthStart = midnightEpochSec(year, month, 1)
        val monthEnd = rollMonthEnd(year, month)
        val todayStart = midnightEpochSec(year, month, day)
        val todayEnd = rollDayEnd(year, month, day)

        // Aggregate per model: month-wide + today-only
        val monthTokensByModel = mutableMapOf<String, TokenBreakdown>()
        val monthCostByModel = mutableMapOf<String, Double>()
        val todayTokensByModel = mutableMapOf<String, TokenBreakdown>()
        val todayCostByModel = mutableMapOf<String, Double>()

        // usage/amount: token counts per model (all API keys summed)
        var amountError: String? = null
        var costError: String? = null
        var amountSeriesCount = 0
        var costSeriesCount = 0
        try {
            val body = execute("$AMOUNT_URL?start=$monthStart&end=$monthEnd&tz=$tzOffsetSec")
            Log.d("DS_AMOUNT", body.take(2000))
            val resp = gson.fromJson(body, UsageByKeyAmountResponse::class.java)
            if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
            val data = resp.data ?: throw Exception("data 为空: ${body.take(200)}")
            if (data.bizCode != 0) throw Exception("biz_code=${data.bizCode} ${data.bizMsg}")
            val biz = data.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
            val series = biz.series ?: emptyList()
            amountSeriesCount = series.size
            for (s in series) {
                val bucket = bucketOf(s.model)
                for (b in s.buckets ?: emptyList()) {
                    val t = b.time ?: continue
                    val u = b.usage ?: continue
                    if (t >= monthStart && t < monthEnd) {
                        val bd = monthTokensByModel.getOrPut(bucket) { TokenBreakdown() }
                        monthTokensByModel[bucket] = bd.add(u)
                        if (t >= todayStart && t < todayEnd) {
                            val td = todayTokensByModel.getOrPut(bucket) { TokenBreakdown() }
                            todayTokensByModel[bucket] = td.add(u)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DS_API", "amount failed", e)
            amountError = e.message ?: "未知错误"
        }

        // usage/cost: per-model cost amounts in CNY (all API keys summed)
        try {
            val body = execute("$COST_URL?start=$monthStart&end=$monthEnd&tz=$tzOffsetSec")
            Log.d("DS_COST", body.take(2000))
            val resp = gson.fromJson(body, UsageByKeyCostResponse::class.java)
            if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
            val data = resp.data ?: throw Exception("data 为空: ${body.take(200)}")
            if (data.bizCode != 0) throw Exception("biz_code=${data.bizCode} ${data.bizMsg}")
            val biz = data.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
            // 平台可能返回多个币种分组（CNY/USD）；优先选“有实际花费”的一组，避免取到空组导致费用全 0
            val blocks = biz.data ?: emptyList()
            fun spendOf(blk: UsageCostCurrency): Double = (blk.series ?: emptyList()).sumOf { s ->
                (s.buckets ?: emptyList()).sumOf { b -> b.cost?.toDoubleOrNull() ?: 0.0 }
            }
            val entry = blocks.firstOrNull { it.currency == "CNY" && spendOf(it) > 0.0 }
                ?: blocks.firstOrNull { spendOf(it) > 0.0 }
                ?: blocks.firstOrNull()
            costSeriesCount = entry?.series?.size ?: 0
            for (s in entry?.series ?: emptyList()) {
                val bucket = bucketOf(s.model)
                for (b in s.buckets ?: emptyList()) {
                    val t = b.time ?: continue
                    val cost = b.cost?.toDoubleOrNull() ?: 0.0
                    if (t >= monthStart && t < monthEnd) {
                        monthCostByModel[bucket] = (monthCostByModel[bucket] ?: 0.0) + cost
                        if (t >= todayStart && t < todayEnd) {
                            todayCostByModel[bucket] = (todayCostByModel[bucket] ?: 0.0) + cost
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DS_API", "cost failed", e)
            costError = e.message ?: "未知错误"
        }

        // 出错时不再静默归零：把真实原因透传到 widget 上
        if (amountError != null && costError != null) {
            throw Exception("用量接口失败: amount[$amountError] cost[$costError]")
        }
        if (amountError == null && costError == null && amountSeriesCount == 0 && costSeriesCount == 0) {
            throw Exception("用量接口返回空数据(series 为空)，请反馈")
        }

        val monthTokensAll = monthTokensByModel.values.sumOf { it.totalTokens }
        val monthCostAll = monthCostByModel.values.sum()

        val flashToday = todayTokensByModel[BUCKET_FLASH] ?: TokenBreakdown()
        val visionToday = todayTokensByModel[BUCKET_VISION] ?: TokenBreakdown()
        val proToday = todayTokensByModel[BUCKET_PRO] ?: TokenBreakdown()
        val flashMonthTk = monthTokensByModel[BUCKET_FLASH]?.totalTokens ?: 0L
        val visionMonthTk = monthTokensByModel[BUCKET_VISION]?.totalTokens ?: 0L
        val proMonthTk = monthTokensByModel[BUCKET_PRO]?.totalTokens ?: 0L

        // 今日总额：对全部模型（含 OTHER）求和，不再只认三个旧模型名
        val todayCostTotal = todayCostByModel.values.sum()

        // Flash / Flash Vision Exp / Pro 卡片仍显示“今日”数据（与旧版行为一致）
        val flashModel = ModelData(
            totalTokens = flashToday.totalTokens,
            cacheHitRate = flashToday.cacheHitRate,
            cost = "%.2f".format(todayCostByModel[BUCKET_FLASH] ?: 0.0),
            requests = flashToday.requests,
            monthlyTokens = flashMonthTk
        )
        // V4.1 起 vision 已并入 flash（平台不再返回 vision 专名）；
        // 若本月/今日完全没有 vision 记录，则 Vision 卡片回显 Flash 数据，而不是显示 0。
        val visionHasData = todayTokensByModel.containsKey(BUCKET_VISION) ||
            monthTokensByModel.containsKey(BUCKET_VISION)
        val visionModel = if (visionHasData) ModelData(
            totalTokens = visionToday.totalTokens,
            cacheHitRate = visionToday.cacheHitRate,
            cost = "%.2f".format(todayCostByModel[BUCKET_VISION] ?: 0.0),
            requests = visionToday.requests,
            monthlyTokens = visionMonthTk
        ) else flashModel.copy()
        val proModel = ModelData(
            totalTokens = proToday.totalTokens,
            cacheHitRate = proToday.cacheHitRate,
            cost = "%.2f".format(todayCostByModel[BUCKET_PRO] ?: 0.0),
            requests = proToday.requests,
            monthlyTokens = proMonthTk
        )

        return MonthUsageResult(
            todayCostTotal = "%.2f".format(todayCostTotal),
            monthCostTotal = "%.2f".format(monthCostAll),
            monthTokens = monthTokensAll,
            flash = flashModel,
            vision = visionModel,
            pro = proModel
        )
    }

    // ─── 诊断（问题反馈用）───────────────────────────────────

    /** 抓取原始响应片段 + 关键参数，供反馈问题时复制排查。 */
    fun fetchDiagnostics(): String {
        val sb = StringBuilder()
        val nowMs = System.currentTimeMillis()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        sb.append("DeepSeekWidget v1.2.2 diag\n")
        sb.append("tz=").append(usageTimeZone.id)
            .append(" offsetSec=").append(usageTimeZone.getOffset(nowMs) / 1000)
            .append(" now=").append(fmt.format(java.util.Date(nowMs))).append('\n')
        val now = Calendar.getInstance(usageTimeZone)
        val y = now.get(Calendar.YEAR)
        val m = now.get(Calendar.MONTH) + 1
        val d = now.get(Calendar.DAY_OF_MONTH)
        val tz = usageTimeZone.getOffset(nowMs) / 1000
        val monthStart = midnightEpochSec(y, m, 1)
        val monthEnd = rollMonthEnd(y, m)
        sb.append("month=[").append(monthStart).append(',').append(monthEnd).append(") ")
            .append("today=[").append(midnightEpochSec(y, m, d)).append(',')
            .append(rollDayEnd(y, m, d)).append(")\n")
        for ((name, url) in listOf(
            "amount" to "$AMOUNT_URL?start=$monthStart&end=$monthEnd&tz=$tz",
            "cost" to "$COST_URL?start=$monthStart&end=$monthEnd&tz=$tz"
        )) {
            sb.append("── ").append(name).append(" ──\n")
            try {
                sb.append(execute(url).take(1800))
            } catch (e: Exception) {
                sb.append("ERR: ").append(e.message)
            }
            sb.append('\n')
        }
        sb.append("── summary ──\n")
        try {
            sb.append(execute(SUMMARY_URL).take(600))
        } catch (e: Exception) {
            sb.append("ERR: ").append(e.message)
        }
        return sb.toString()
    }

    // ─── 时间计算（基于用量时区）────────────────────────────────

    /** Local midnight (00:00 in usageTimeZone) as epoch seconds. */
    private fun midnightEpochSec(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis / 1000
    }

    /** First day of next month (exclusive end of the current month range). */
    private fun rollMonthEnd(year: Int, month: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, 1, 0, 0, 0)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis / 1000
    }

    /** Day after the given day (exclusive end of the "today" range). */
    private fun rollDayEnd(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis / 1000
    }

    // ─── HTTP ─────────────────────────────────────────────────

    private fun execute(url: String): String {
        val request = buildRequest(url)
        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            throw when {
                response.code == 401 -> Exception("登录已过期，请重新登录")
                response.code == 429 || body.trimStart().startsWith("<") ->
                    Exception("被平台拦截（HTTP ${response.code}），请稍后重试")
                else -> Exception("API ${response.code}: ${body.take(160)}")
            }
        }
        // WAF 拦截页可能以 200 返回 HTML
        if (body.trimStart().startsWith("<")) throw Exception("被平台拦截（非 JSON 响应），请稍后重试")
        return body
    }
}

// ═══════════════════════════════════════════════════════════════
//  Response DTOs
// ═══════════════════════════════════════════════════════════════

data class SummaryResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: SummaryData? = null
)

data class SummaryData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: SummaryBizData? = null
)

data class SummaryBizData(
    @SerializedName("current_token") val currentToken: Long? = null,
    @SerializedName("monthly_usage") val monthlyUsage: String? = null,
    @SerializedName("total_usage") val totalUsage: Long? = null,
    @SerializedName("normal_wallets") val normalWallets: List<Wallet>? = null,
    @SerializedName("bonus_wallets") val bonusWallets: List<Wallet>? = null,
    @SerializedName("total_available_token_estimation") val totalAvailableTokenEstimation: String? = null,
    // 2026-08 platform update: monthly_costs → total_costs (kept both for compatibility)
    @SerializedName("monthly_costs") val monthlyCosts: List<MonthlyCost>? = null,
    @SerializedName("total_costs") val totalCosts: List<MonthlyCost>? = null,
    @SerializedName("monthly_token_usage") val monthlyTokenUsage: String? = null
)

data class Wallet(
    val currency: String? = null,
    val balance: String? = null,
    @SerializedName("token_estimation") val tokenEstimation: String? = null
)

data class MonthlyCost(
    val currency: String? = null,
    val amount: String? = null
)

// ── usage/by_api_key/amount（2026-08 新端点）────────────────────

data class UsageByKeyAmountResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageByKeyAmountData? = null
)

data class UsageByKeyAmountData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: UsageByKeyAmountBiz? = null
)

data class UsageByKeyAmountBiz(
    val start: Long? = null,
    val end: Long? = null,
    val bucket: Long? = null,
    val models: List<String>? = null,
    val series: List<UsageByKeySeries>? = null
)

data class UsageByKeySeries(
    // 实际是对象（含 tracking_id/name 等元信息），不是字符串；本应用只按 model 汇总，忽略内容
    @SerializedName("api_key") val apiKey: JsonElement? = null,
    val model: String? = null,
    val buckets: List<UsageBucket>? = null
)

data class UsageBucket(
    val time: Long? = null,
    val usage: UsageByKeyUsage? = null
)

data class UsageByKeyUsage(
    @SerializedName("PROMPT_TOKEN") val promptToken: String? = null,
    @SerializedName("PROMPT_CACHE_HIT_TOKEN") val cacheHitToken: String? = null,
    @SerializedName("PROMPT_CACHE_MISS_TOKEN") val cacheMissToken: String? = null,
    @SerializedName("RESPONSE_TOKEN") val responseToken: String? = null,
    @SerializedName("REQUEST") val request: String? = null
)


// ── usage/by_api_key/cost（2026-08 新端点）──────────────────────

data class UsageByKeyCostResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageByKeyCostData? = null
)

data class UsageByKeyCostData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: UsageByKeyCostBiz? = null
)

data class UsageByKeyCostBiz(
    val start: Long? = null,
    val end: Long? = null,
    val bucket: Long? = null,
    val models: List<String>? = null,
    val data: List<UsageCostCurrency>? = null
)

data class UsageCostCurrency(
    val currency: String? = null,
    val series: List<UsageByKeyCostSeries>? = null
)

data class UsageByKeyCostSeries(
    // 实际是对象（含 tracking_id/name 等元信息），不是字符串；本应用只按 model 汇总，忽略内容
    @SerializedName("api_key") val apiKey: JsonElement? = null,
    val model: String? = null,
    val buckets: List<CostBucket>? = null
)

data class CostBucket(
    val time: Long? = null,
    val cost: String? = null
)
