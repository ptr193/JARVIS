package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Smart_devices(
  public val id: String,
  public val name: String,
  public val type: String,
  public val online: Long,
)
