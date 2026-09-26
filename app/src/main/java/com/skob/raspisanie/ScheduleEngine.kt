package com.skob.raspisanie

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

data class PeriodEntry(
    val period: Int,
    val subject: String,
    val online: Boolean
)

data class Reminder(
    val hour: Int,
    val minute: Int,
    val daysAhead: Int
)

data class DayConfig(
    val reminders: List<Reminder>,
    val travelMinutes: Int,
    val prepMinutes: Int,
    val homeLat: Double,
    val homeLon: Double,
    val exemptSubjects: Set<String>,
    val periodTimes: Map<Int, Pair<LocalTime, LocalTime>>,
    val weekSchedule: Map<DayOfWeek, List<PeriodEntry>>,
    val itemsBySubject: Map<String, List<String>>,
    val alwaysBring: List<String>
)

object ScheduleEngine {

    fun loadConfig(context: Context): DayConfig {
        val text = context.assets.open("config.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)

        val remindersJson = json.getJSONArray("reminders")
        val reminders = mutableListOf<Reminder>()
        for (i in 0 until remindersJson.length()) {
            val obj = remindersJson.getJSONObject(i)
            reminders.add(
                Reminder(
                    hour = obj.getInt("hour"),
                    minute = obj.getInt("minute"),
                    daysAhead = obj.getInt("days_ahead")
                )
            )
        }

        val periodTimesJson = json.getJSONArray("period_times")
        val periodTimes = mutableMapOf<Int, Pair<LocalTime, LocalTime>>()
        for (i in 0 until periodTimesJson.length()) {
            val obj = periodTimesJson.getJSONObject(i)
            periodTimes[obj.getInt("period")] = Pair(
                LocalTime.parse(obj.getString("start")),
                LocalTime.parse(obj.getString("end"))
            )
        }

        val exemptJson = json.optJSONArray("exempt_subjects")
        val exemptSubjects = mutableSetOf<String>()
        if (exemptJson != null) {
            for (i in 0 until exemptJson.length()) exemptSubjects.add(exemptJson.getString(i))
        }

        val alwaysBringJson = json.optJSONArray("always_bring")
        val alwaysBring = mutableListOf<String>()
        if (alwaysBringJson != null) {
            for (i in 0 until alwaysBringJson.length()) alwaysBring.add(alwaysBringJson.getString(i))
        }

        val weekJson = json.getJSONObject("week_schedule")
        val week = mutableMapOf<DayOfWeek, List<PeriodEntry>>()
        for (day in DayOfWeek.values()) {
            val arr = weekJson.optJSONArray(day.name)
            val list = mutableListOf<PeriodEntry>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    list.add(
                        PeriodEntry(
                            period = obj.getInt("period"),
                            subject = obj.getString("subject"),
                            online = obj.optBoolean("online", false)
                        )
                    )
                }
            }
            week[day] = list
        }

        val itemsJson = json.optJSONObject("items_by_subject")
        val items = mutableMapOf<String, List<String>>()
        if (itemsJson != null) {
            val keys = itemsJson.keys()
            while (keys.hasNext()) {
                val subject = keys.next()
                val arr = itemsJson.getJSONArray(subject)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                items[subject] = list
            }
        }

        return DayConfig(
            reminders = reminders,
            travelMinutes = json.optInt("travel_minutes", 20),
            prepMinutes = json.optInt("prep_minutes", 15),
            homeLat = json.optDouble("home_lat", 0.0),
            homeLon = json.optDouble("home_lon", 0.0),
            exemptSubjects = exemptSubjects,
            periodTimes = periodTimes,
            weekSchedule = week,
            itemsBySubject = items,
            alwaysBring = alwaysBring
        )
    }

    fun buildMessage(config: DayConfig, daysAhead: Int): String {
        val targetDate = LocalDate.now().plusDays(daysAhead.toLong())
        val dayOfWeek = targetDate.dayOfWeek
        val dayName = dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
            .replaceFirstChar { it.uppercase() }

        val entries = (config.weekSchedule[dayOfWeek] ?: emptyList()).sortedBy { it.period }

        if (entries.isEmpty()) {
            return "$dayName, $targetDate — пар нет."
        }

        val entryByPeriod = entries.associateBy { it.period }
        val minPeriod = entries.minOf { it.period }
        val maxPeriod = entries.maxOf { it.period }

        // Первая пара, на которую реально нужно ехать (не освобождение и не онлайн)
        val firstInPerson = entries.firstOrNull {
            it.subject !in config.exemptSubjects && !it.online
        }

        val sb = StringBuilder()
        sb.append("$dayName, $targetDate\n")

        var period = minPeriod
        while (period <= maxPeriod) {
            val entry = entryByPeriod[period]
            if (entry == null) {
                var end = period
                while (end + 1 <= maxPeriod && entryByPeriod[end + 1] == null) end++
                sb.append(
                    if (end == period) "$period пара — окно\n"
                    else "$period-$end пара — окно\n"
                )
                period = end + 1
                continue
            }
            val times = config.periodTimes[entry.period]
            val timeLabel = if (times != null) " (${times.first}–${times.second})" else ""
            val suffix = when {
                entry.subject in config.exemptSubjects -> " — освобождение, не идёшь"
                entry.online -> " — онлайн, из дома"
                else -> ""
            }
            sb.append("${entry.period} пара$timeLabel — ${entry.subject}$suffix\n")
            period++
        }

        if (firstInPerson != null) {
            val startTime = config.periodTimes[firstInPerson.period]?.first
            if (startTime != null) {
                val departureTime = startTime
                    .minusMinutes(config.prepMinutes.toLong())
                    .minusMinutes(config.travelMinutes.toLong())
                sb.append("время выхода: $departureTime\n")

                val weather = try {
                    fetchWeather(config.homeLat, config.homeLon, targetDate, departureTime)
                } catch (e: Exception) {
                    null
                }
                if (weather != null) {
                    sb.append("погода: при выходе ${weather.first}°C, днём до ${weather.second}°C\n")
                }
            }
        } else {
            sb.append("очных пар нет — из дома выходить не нужно\n")
        }

        val items = (
            entries
                .filter { it.subject !in config.exemptSubjects }
                .flatMap { config.itemsBySubject[it.subject] ?: emptyList() } + config.alwaysBring
            ).distinct()
        if (items.isNotEmpty()) {
            sb.append("взять: ${items.joinToString(", ")}")
        }

        return sb.toString().trim()
    }

    private fun fetchWeather(
        lat: Double,
        lon: Double,
        date: LocalDate,
        departureTime: LocalTime
    ): Pair<Int, Int> {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&hourly=temperature_2m&daily=temperature_2m_max&forecast_days=3&timezone=auto"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10000
        conn.readTimeout = 10000
        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(response)

        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val targetHourString = "%04d-%02d-%02dT%02d:00".format(
            date.year, date.monthValue, date.dayOfMonth, departureTime.hour
        )
        var departureTemp = temps.getDouble(0)
        for (i in 0 until times.length()) {
            if (times.getString(i) == targetHourString) {
                departureTemp = temps.getDouble(i)
                break
            }
        }

        val daily = json.getJSONObject("daily")
        val dailyTimes = daily.getJSONArray("time")
        val dailyMax = daily.getJSONArray("temperature_2m_max")
        val targetDateString = "%04d-%02d-%02d".format(date.year, date.monthValue, date.dayOfMonth)
        var maxTemp = dailyMax.getDouble(0)
        for (i in 0 until dailyTimes.length()) {
            if (dailyTimes.getString(i) == targetDateString) {
                maxTemp = dailyMax.getDouble(i)
                break
            }
        }

        return Pair(departureTemp.toInt(), maxTemp.toInt())
    }
}
