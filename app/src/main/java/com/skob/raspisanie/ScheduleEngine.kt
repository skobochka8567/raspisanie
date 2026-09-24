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

data class DayConfig(
    val notifyHour: Int,
    val notifyMinute: Int,
    val daysAhead: Int,
    val firstPeriodTime: LocalTime,
    val travelMinutes: Int,
    val prepMinutes: Int,
    val homeLat: Double,
    val homeLon: Double,
    val weekSchedule: Map<DayOfWeek, List<String>>,
    val itemsBySubject: Map<String, List<String>>
)

object ScheduleEngine {

    fun loadConfig(context: Context): DayConfig {
        val text = context.assets.open("config.json").bufferedReader().use { it.readText() }
        val json = JSONObject(text)

        val weekJson = json.getJSONObject("week_schedule")
        val week = mutableMapOf<DayOfWeek, List<String>>()
        for (day in DayOfWeek.values()) {
            val arr = weekJson.optJSONArray(day.name)
            val list = mutableListOf<String>()
            if (arr != null) {
                for (i in 0 until arr.length()) list.add(arr.getString(i))
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
            notifyHour = json.optInt("notify_hour", 20),
            notifyMinute = json.optInt("notify_minute", 0),
            daysAhead = json.optInt("days_ahead", 1),
            firstPeriodTime = LocalTime.parse(json.optString("first_period_time", "08:30")),
            travelMinutes = json.optInt("travel_minutes", 20),
            prepMinutes = json.optInt("prep_minutes", 15),
            homeLat = json.optDouble("home_lat", 0.0),
            homeLon = json.optDouble("home_lon", 0.0),
            weekSchedule = week,
            itemsBySubject = items
        )
    }

    fun buildMessage(config: DayConfig): String {
        val targetDate = LocalDate.now().plusDays(config.daysAhead.toLong())
        val dayOfWeek = targetDate.dayOfWeek
        val subjects = config.weekSchedule[dayOfWeek] ?: emptyList()
        val dayName = dayOfWeek.getDisplayName(TextStyle.FULL, Locale("ru"))
            .replaceFirstChar { it.uppercase() }

        if (subjects.isEmpty()) {
            return "$dayName, $targetDate — пар нет."
        }

        val departureTime = config.firstPeriodTime
            .minusMinutes(config.prepMinutes.toLong())
            .minusMinutes(config.travelMinutes.toLong())

        val items = subjects
            .flatMap { config.itemsBySubject[it] ?: emptyList() }
            .distinct()

        val weather = try {
            fetchWeather(config.homeLat, config.homeLon, targetDate, departureTime)
        } catch (e: Exception) {
            null
        }

        val sb = StringBuilder()
        sb.append("$dayName, $targetDate\n")
        subjects.forEachIndexed { index, subject ->
            sb.append("${index + 1} пара — $subject\n")
        }
        sb.append("время выхода: $departureTime\n")
        if (weather != null) {
            sb.append("погода: при выходе ${weather.first}°C, днём до ${weather.second}°C\n")
        }
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
