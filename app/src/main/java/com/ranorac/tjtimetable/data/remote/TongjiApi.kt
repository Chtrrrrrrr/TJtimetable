package com.ranorac.tjtimetable.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Failure modes callers need to distinguish, especially [UNAUTHORIZED]. */
class TongjiApiException(
    message: String,
    val kind: Kind,
    /** The platform's own code, e.g. `A06500`, when there was one. */
    val apiCode: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** Token expired or lacks the scope — the caller should re-authorise. */
        UNAUTHORIZED,

        /** The platform returned a non-OK `code`. */
        API_ERROR,

        /** Network or HTTP-level failure. */
        TRANSPORT,

        /** The response did not look like the documented envelope. */
        MALFORMED,
    }
}

/**
 * Client for 同济大学开放平台.
 *
 * Endpoint and field names follow the platform docs; the two timetable
 * endpoints are deliberately both implemented because they fail differently:
 *
 *  - `v1/rt/onetongji/student_timetable` is **real-time** and nested, and is the
 *    one that returns a resolved `weeks` array, so it is the primary source.
 *  - `v2/dc/teaching_info/student_timetable` is a **batch** endpoint with a
 *    `sinceId` cursor. It is the fallback when the real-time one is down, and the
 *    only one that can page a whole cohort.
 *
 * Note that two useful endpoints — 学年学期信息 and 校历信息 — need **no** token at
 * all, which is what lets the app show 调休信息 before the student logs in.
 */
class TongjiApi(
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson(),
) {

    // ------------------------------------------------------------- auth

    /**
     * OAuth2 客户端模式. Requires a `client_id`/`client_secret` issued to the
     * app by the 开放平台, so it is only usable for a registered client.
     */
    suspend fun clientCredentialsToken(clientId: String, clientSecret: String): TokenResponse {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("grant_type", "client_credentials")
            .build()
        return requestToken(form)
    }

    /** OAuth2 授权码模式 — exchanges the `code` from the 统一身份认证 redirect. */
    suspend fun authorizationCodeToken(
        clientId: String,
        clientSecret: String,
        code: String,
        redirectUri: String,
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .build()
        return requestToken(form)
    }

    private suspend fun requestToken(form: FormBody): TokenResponse {
        val request = Request.Builder()
            .url("$BASE/v1/token")
            .post(form)
            .build()
        val body = execute(request)
        val token = runCatching { json.decodeFromString(TokenResponse.serializer(), body) }.getOrNull()
            ?: throw TongjiApiException("无法解析登录响应", TongjiApiException.Kind.MALFORMED)
        if (token.accessToken.isNullOrBlank()) {
            throw TongjiApiException("登录失败：未获得访问令牌", TongjiApiException.Kind.UNAUTHORIZED)
        }
        return token
    }

    /**
     * Builds the URL to open in a Custom Tab for 授权码模式.
     *
     * `kc_idp_hint=tjiam` is required by the platform — it selects 同济统一身份认证
     * as the identity provider; omitting it shows the wrong login page.
     */
    fun authorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        scope: String = DEFAULT_SCOPE,
    ): String = HttpUrl.Builder()
        .scheme("https")
        .host(AUTH_HOST)
        .addPathSegments(AUTH_PATH)
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("redirect_uri", redirectUri)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("scope", scope)
        .addQueryParameter("state", state)
        .addQueryParameter("kc_idp_hint", IDP_HINT)
        .build()
        .toString()

    // --------------------------------------------------------- 课表

    /**
     * 学生学期课表信息 (实时).
     *
     * @param calendarId 学期编号; null asks for the current semester.
     */
    suspend fun studentTimetable(
        token: String,
        userId: String,
        calendarId: String? = null,
    ): List<StudentCourseDto> {
        val url = urlBuilder("/v1/rt/onetongji/student_timetable")
            .addQueryParameter("userId", userId)
            .apply { if (!calendarId.isNullOrBlank()) addQueryParameter("calendarId", calendarId) }
            .build()
        val body = execute(get(url, token))
        return envelope(body, ListSerializer(StudentCourseDto.serializer()))
    }

    /**
     * 学生本学期课表信息 (批量/数据中心), paging the `sinceId` cursor to the end.
     *
     * @param sinceUpdateTime fetch only rows changed after this time; null for a
     *   full pull. Format is `yyyy-MM-dd HH:mm:ss`.
     */
    suspend fun studentTimetableBatch(
        token: String,
        userId: String,
        sinceUpdateTime: String? = null,
        maxPages: Int = MAX_BATCH_PAGES,
    ): List<StudentTimetableRowDto> {
        val out = ArrayList<StudentTimetableRowDto>()
        var sinceId: String? = null
        var page = 0
        while (page < maxPages) {
            val url = urlBuilder("/v2/dc/teaching_info/student_timetable")
                .addQueryParameter("userId", userId)
                .apply {
                    if (!sinceUpdateTime.isNullOrBlank()) addQueryParameter("sinceUpdateTime", sinceUpdateTime)
                    if (!sinceId.isNullOrBlank()) addQueryParameter("sinceId", sinceId)
                }
                .build()
            val body = execute(get(url, token))
            val rows = envelope(body, ListSerializer(StudentTimetableRowDto.serializer()))
            if (rows.isEmpty()) break
            out.addAll(rows)
            // The cursor is the last row's own id; an empty page means we are done.
            sinceId = rows.lastOrNull()?.id
            if (sinceId.isNullOrBlank()) break
            page++
        }
        return out
    }

    // --------------------------------------------------------- 学期/校历

    /** 学年学期信息 — 无需授权. */
    suspend fun semesters(): List<SemesterDto> {
        val body = execute(get(urlBuilder("/v1/rt/teaching_info/semester").build(), token = null))
        return envelope(body, ListSerializer(SemesterDto.serializer()))
    }

    /** 当前学期日历编号 — requires a token. */
    suspend fun currentTermCalendar(token: String): CurrentTermCalendarDto {
        val body = execute(get(urlBuilder("/v1/rt/onetongji/school_calendar_current_term_calendar").build(), token))
        return envelope(body, CurrentTermCalendarDto.serializer())
    }

    /**
     * 校历信息 — 无需授权. Returns a `yyyy-MM-dd -> kind` map where kind is
     * `1`=节假日 `2`=工作日(含调休) `3`=周末 `4`=寒假 `5`=暑期.
     *
     * Only dates from 2023-01-01 onward are recorded, and a range with no data
     * returns [ApiCode.CALENDAR_RANGE_EMPTY] — treated here as "no information"
     * rather than an error, because that is exactly what it means.
     */
    suspend fun schoolCalendar(from: LocalDate? = null, to: LocalDate? = null): Map<String, String> {
        val url = urlBuilder("/v1/rt/onetongji/calendar")
            .apply {
                from?.let { addQueryParameter("fromDate", DATE_FMT.format(it)) }
                to?.let { addQueryParameter("endDate", DATE_FMT.format(it)) }
            }
            .build()
        val body = execute(get(url, token = null))
        val env = runCatching {
            json.decodeFromString(
                ApiEnvelope.serializer(MapSerializer(String.serializer(), String.serializer())),
                body,
            )
        }.getOrNull() ?: throw TongjiApiException("无法解析校历响应", TongjiApiException.Kind.MALFORMED)

        if (env.code != null && env.code != ApiCode.OK) {
            if (env.code == ApiCode.CALENDAR_RANGE_EMPTY) return emptyMap()
            throw TongjiApiException(env.msg ?: "校历接口返回 ${env.code}", TongjiApiException.Kind.API_ERROR, env.code)
        }
        return env.data ?: emptyMap()
    }

    // --------------------------------------------------------- internals

    private fun get(url: HttpUrl, token: String?): Request =
        Request.Builder()
            .url(url)
            .apply { if (!token.isNullOrBlank()) header("Authorization", "Bearer $token") }
            .get()
            .build()

    private fun urlBuilder(path: String): HttpUrl.Builder =
        BASE.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/'))

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.code == 401 ->
                        throw TongjiApiException("登录状态已失效，请重新登录", TongjiApiException.Kind.UNAUTHORIZED)
                    response.code == 403 ->
                        throw TongjiApiException(
                            "该账号未被授权访问此接口，请确认已在开放平台申请权限",
                            TongjiApiException.Kind.UNAUTHORIZED,
                        )
                    !response.isSuccessful ->
                        throw TongjiApiException("服务器返回 ${response.code}", TongjiApiException.Kind.TRANSPORT)
                    else -> body
                }
            }
        } catch (e: TongjiApiException) {
            throw e
        } catch (e: IOException) {
            throw TongjiApiException("网络连接失败：${e.message ?: e::class.java.simpleName}", TongjiApiException.Kind.TRANSPORT, cause = e)
        }
    }

    private fun <T> envelope(body: String, dataSerializer: KSerializer<T>): T {
        val env = try {
            json.decodeFromString(ApiEnvelope.serializer(dataSerializer), body)
        } catch (e: Exception) {
            throw TongjiApiException("无法解析服务器响应", TongjiApiException.Kind.MALFORMED, cause = e)
        }
        if (env.code != null && env.code != ApiCode.OK) {
            throw TongjiApiException(env.msg ?: "接口返回 ${env.code}", TongjiApiException.Kind.API_ERROR, env.code)
        }
        return env.data ?: throw TongjiApiException("接口未返回数据", TongjiApiException.Kind.MALFORMED)
    }

    companion object {
        const val BASE = "https://api.tongji.edu.cn"
        const val AUTH_HOST = "api.tongji.edu.cn"

        /** Keycloak realm used by the 开放平台. */
        const val AUTH_PATH = "keycloak/realms/OpenPlatform/protocol/openid-connect/auth"

        /**
         * The platform requires this fixed value; it selects 同济统一身份认证 as
         * the identity provider. Any other value (or omitting it) shows the wrong
         * login page.
         */
        const val IDP_HINT = "tjiam"

        /**
         * The scope has to name the 课表 endpoints explicitly; the platform
         * grants scopes per interface rather than in bulk.
         */
        const val DEFAULT_SCOPE =
            "openid v1_rt_onetongji_student_timetable v1_rt_onetongji_school_calendar_current_term_calendar"

        const val MAX_BATCH_PAGES = 20

        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            explicitNulls = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
