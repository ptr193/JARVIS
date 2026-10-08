package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Skills(
  public val id: String,
  public val name: String,
  public val package_name: String?,
  public val installed: Long,
)
