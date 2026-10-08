package com.jarvis.pineapple.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.jarvis.pineapple.db.app.newInstance
import com.jarvis.pineapple.db.app.schema
import kotlin.Unit

public interface JarvisDatabase : Transacter {
  public val jarvisQueries: JarvisQueries

  public companion object {
    public val Schema: SqlSchema<QueryResult.Value<Unit>>
      get() = JarvisDatabase::class.schema

    public operator fun invoke(driver: SqlDriver): JarvisDatabase =
        JarvisDatabase::class.newInstance(driver)
  }
}
