package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Messages(
  public val id: String,
  public val conversation_id: String,
  public val role: String,
  public val content: String,
  public val timestamp: Long,
)
