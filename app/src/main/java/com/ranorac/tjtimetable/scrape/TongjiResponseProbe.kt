package com.ranorac.tjtimetable.scrape

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 抓回来的响应该不该交给适配器（`ok = false` 时 [note] 直接给用户看）。 */
data class TongjiResponseVerdict(val ok: Boolean, val note: String)

/**
 * 判断一段响应文本「像不像课表数据」。
 *
 * Ported from `gzy31007/TJDesktopTimetable`'s `dotnet/TjtCore/TongjiResponseProbe.cs`,
 * which is itself a port of the TS side's `looksLikeTimetable`. It is pure
 * "look at the response" logic, so it is unit-testable without a window or a network.
 *
 * 它不是适配器的替代品：这里只回答「要不要往下走」，真正的字段解析在
 * [TongjiStudentAdapter] 里。给用户的诊断信息（探测结果）也来自这里 ——
 * 用户粘错请求（拿到登录页 HTML、拿到别的接口）时，这句话就是他唯一的线索。
 * 因此 [note] 的分支顺序与文案都与参考实现逐条对齐。
 */
object TongjiResponseProbe {

    /** 诊断信息里字段名的最大展示个数（与参考实现一致）。 */
    private const val MAX_FIELD_NAMES = 8

    /** 服务端 message 的最大展示长度（与参考实现一致）。 */
    private const val MAX_MESSAGE_LENGTH = 80

    /**
     * Strict, like `JsonDocument.Parse`: the probe's whole job is to tell a real
     * payload from a login page, so leniency here would defeat it.
     */
    private val json = Json

    /** 检查响应文本。永不抛异常：不合法的 JSON 也变成一条可读的诊断。 */
    fun inspect(text: String?): TongjiResponseVerdict {
        if (text.isNullOrBlank()) return TongjiResponseVerdict(false, "响应为空")

        val payload = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return notJson()
        } catch (_: IllegalArgumentException) {
            return notJson()
        }

        return inspect(payload)
    }

    private fun inspect(payload: JsonElement): TongjiResponseVerdict {
        if (payload !is JsonObject) return TongjiResponseVerdict(false, "响应不是 JSON 对象")

        val hasData = payload.containsKey("data")
        val data = payload["data"]
        if (!hasData && payload.containsKey("message")) {
            // 同济教务失败的典型样子：{code: 500, message: "..."}，没有 data
            val message = payload["message"]
            // C# 的 `JsonElement.ToString()` 对 JsonTokenType.Null 返回空串，对字符串返回原文。
            val text = when {
                message == null || message is JsonNull -> ""
                message is JsonPrimitive && message.isString -> message.content
                else -> message.toString()
            }
            return TongjiResponseVerdict(false, "服务端返回：${truncate(text, MAX_MESSAGE_LENGTH)}")
        }

        if (hasData && data is JsonObject) {
            val selected = data["selectedCourses"]
            if (data.containsKey("selectedCourses") && selected is JsonArray) {
                return TongjiResponseVerdict(true, "selectedCourses ${selected.size} 门")
            }

            return TongjiResponseVerdict(false, "data 字段：${fieldNames(data)}")
        }

        if (hasData && data is JsonArray) {
            return TongjiResponseVerdict(true, "data 数组 ${data.size} 条")
        }

        return TongjiResponseVerdict(false, "顶层字段：${fieldNames(payload)}")
    }

    private fun notJson() = TongjiResponseVerdict(
        false,
        "响应不是合法 JSON（可能复制到了 HTML 页面请求，请换成 getDataBk 那条）。",
    )

    /** 前 [MAX_FIELD_NAMES] 个字段名，逗号加空格分隔（与 `string.Join(", ", …)` 一致）。 */
    private fun fieldNames(element: JsonObject): String =
        element.keys.take(MAX_FIELD_NAMES).joinToString(", ")

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max)
}
