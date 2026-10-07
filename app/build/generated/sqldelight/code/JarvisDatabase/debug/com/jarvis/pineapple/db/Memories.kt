package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Memories(
  public val key: String,
  public val value_: String,
  public val category: String?,
  public val updated_at: Long,
)
