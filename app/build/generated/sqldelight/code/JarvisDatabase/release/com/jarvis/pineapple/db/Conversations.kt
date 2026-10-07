package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Conversations(
  public val id: String,
  public val title: String,
  public val created_at: Long,
)
