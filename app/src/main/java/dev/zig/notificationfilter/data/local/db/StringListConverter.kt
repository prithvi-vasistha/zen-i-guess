package dev.zig.notificationfilter.data.local.db

import androidx.room.TypeConverter
import org.json.JSONArray

class StringListConverter {

    @TypeConverter
    fun fromList(list: List<String>): String = JSONArray(list).toString()

    @TypeConverter
    fun toList(json: String): List<String> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
