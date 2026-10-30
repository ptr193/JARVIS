package com.jarvis.pineapple.db

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * SQLDelight 数据库驱动工厂
 */
object DatabaseFactory {
    fun create(context: Context): JarvisDatabase {
        val driver = AndroidSqliteDriver(JarvisDatabase.Schema, context, "jarvis.db")
        return JarvisDatabase(driver)
    }
}
