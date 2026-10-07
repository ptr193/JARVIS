package com.jarvis.pineapple.db.app

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.jarvis.pineapple.db.JarvisDatabase
import com.jarvis.pineapple.db.JarvisQueries
import kotlin.Long
import kotlin.Unit
import kotlin.reflect.KClass

internal val KClass<JarvisDatabase>.schema: SqlSchema<QueryResult.Value<Unit>>
  get() = JarvisDatabaseImpl.Schema

internal fun KClass<JarvisDatabase>.newInstance(driver: SqlDriver): JarvisDatabase =
    JarvisDatabaseImpl(driver)

private class JarvisDatabaseImpl(
  driver: SqlDriver,
) : TransacterImpl(driver), JarvisDatabase {
  override val jarvisQueries: JarvisQueries = JarvisQueries(driver)

  public object Schema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long
      get() = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS conversations (
          |    id TEXT PRIMARY KEY,
          |    title TEXT NOT NULL,
          |    created_at INTEGER NOT NULL
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS messages (
          |    id TEXT PRIMARY KEY,
          |    conversation_id TEXT NOT NULL,
          |    role TEXT NOT NULL,
          |    content TEXT NOT NULL,
          |    timestamp INTEGER NOT NULL,
          |    FOREIGN KEY(conversation_id) REFERENCES conversations(id) ON DELETE CASCADE
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS skills (
          |    id TEXT PRIMARY KEY,
          |    name TEXT NOT NULL,
          |    package_name TEXT,
          |    installed INTEGER NOT NULL DEFAULT 0
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS vm_instances (
          |    id TEXT PRIMARY KEY,
          |    name TEXT NOT NULL,
          |    distro TEXT NOT NULL,
          |    state TEXT NOT NULL DEFAULT 'STOPPED',
          |    created_at INTEGER NOT NULL
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS smart_devices (
          |    id TEXT PRIMARY KEY,
          |    name TEXT NOT NULL,
          |    type TEXT NOT NULL,
          |    online INTEGER NOT NULL DEFAULT 0
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS health_records (
          |    id TEXT PRIMARY KEY,
          |    heart_rate INTEGER,
          |    temperature REAL,
          |    timestamp INTEGER NOT NULL
          |)
          """.trimMargin(), 0)
      driver.execute(null, """
          |CREATE TABLE IF NOT EXISTS memories (
          |    key TEXT PRIMARY KEY,
          |    value TEXT NOT NULL,
          |    category TEXT,
          |    updated_at INTEGER NOT NULL
          |)
          """.trimMargin(), 0)
      return QueryResult.Unit
    }

    override fun migrate(
      driver: SqlDriver,
      oldVersion: Long,
      newVersion: Long,
      vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
  }
}
