package com.jarvis.pineapple.db

import kotlin.Double
import kotlin.Long
import kotlin.String

public data class Health_records(
  public val id: String,
  public val heart_rate: Long?,
  public val temperature: Double?,
  public val timestamp: Long,
)
