package com.ayham.camcurrency

import android.os.Handler
import android.content.Context
import android.os.Build
import android.provider.Contacts
import androidx.annotation.RequiresApi
import androidx.room.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/// This file manages and stores forex rates from the website
// http://www.floatrates.com/daily/usd.xml
// All currencies assume USD as a base currency

@Entity(tableName = "rates")
data class Rate(
    @PrimaryKey(autoGenerate = true) val uid: Int = 0,
    @ColumnInfo(name = "targetCurrency") var targetCurrency: String? = "",
    @ColumnInfo(name = "targetName") var targetName: String? = "",
    @ColumnInfo(name = "exchangeRate") var exchangeRate: Double = 0.0,
    @ColumnInfo(name = "inverseRate") var inverseRate: Double = 0.0,
)

@Dao
interface RateDao {
    @Query("SELECT * FROM rates")
    fun getAll(): List<Rate>

    @Query("SELECT * FROM rates where targetCurrency LIKE :target")
    fun findByTargetCurrency(target: String): Rate

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(vararg rates: Rate)

    @Query("DELETE FROM rates")
    fun nukeTable()
}

@Database(entities = [Rate::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rateDao(): RateDao
}

class FloatRate(
    private val context: Context,
    private val adHandler: Handler
) {
    public lateinit var xml: String
    private var db: AppDatabase = Room.databaseBuilder(
        this.context,
        AppDatabase::class.java, DATABASE_NAME
    ).allowMainThreadQueries().build()
    public var ratesDao = this.db.rateDao()

    init {
        // Check if update of our rates is required
        // update every 12 hours
        if (this.checkIf12HoursPassed()) {
            // if data is not empty, then second time opening, run Ad
            if (this.ratesDao.getAll().isNotEmpty())
                this.adHandler.sendEmptyMessage(0)
            this.retrieveRatesData()
        }
    }

    private fun retrieveRatesData() {
        // SEND NUKES
        this.ratesDao.nukeTable()

        // Always add USD
        this.ratesDao.insertAll(Rate(0, "USD", "US Dollar", 1.0, 1.0))

        // Get the data from floatrates.com
        this.xml = java.net.URL(WEBSITE_XML).readText()

        // Parse the goddam XML
        try {
            var rate = Rate()
            val parserFactory: XmlPullParserFactory = XmlPullParserFactory.newInstance()
            val parser: XmlPullParser = parserFactory.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(xml.byteInputStream(), null)
            var tag: String?
            var text = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                tag = parser.name
                when (event) {
                    XmlPullParser.START_TAG -> if (tag == "item") rate = Rate()
                    XmlPullParser.TEXT -> text = parser.text
                    XmlPullParser.END_TAG -> when (tag) {
                        "targetCurrency" -> rate.targetCurrency = text
                        "targetName" -> rate.targetName = text
                        "exchangeRate" -> rate.exchangeRate = text.replace(",", "").toDouble()
                        "inverseRate" -> rate.inverseRate = text.replace(",", "").toDouble()
                        "item" -> if (rate !== Rate()) this.ratesDao.insertAll(rate)
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            return
        }
    }

    private fun checkIf12HoursPassed(): Boolean {
        // read date
        val currentTime = LocalDateTime.now()
        val path = this.context.filesDir
        val file = File(path, DATE_FILE_NAME)
        var rawDate: String = ""
        if (file.exists()) {
            rawDate = FileInputStream(file).bufferedReader().use { it.readText() }
            // check date
            val lastUpdateTime = LocalDateTime.parse(rawDate)
            val hoursDifference = (currentTime.toEpochSecond(ZONE_OFFSET) -
                    lastUpdateTime.toEpochSecond(ZONE_OFFSET)) / (60*60)
            return hoursDifference >= UPDATE_HOURS
        }
        else {
            rawDate = LocalDateTime.now().toString()

            // write date
            file.delete()
            FileOutputStream(file).use {
                it.write(currentTime.toString().toByteArray())
            }
            return true
        }
    }

    companion object {
        private const val WEBSITE_XML = "https://www.floatrates.com/daily/usd.xml"
        private const val DATABASE_NAME = "floatrates"
        private const val DATE_FILE_NAME = "lastUpdate.txt"
        private const val UPDATE_HOURS = 24
        private val ZONE_OFFSET = ZoneOffset.UTC
    }
}