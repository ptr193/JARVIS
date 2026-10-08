package com.jarvis.pineapple.db

import kotlin.Long
import kotlin.String

public data class Vm_instances(
  public val id: String,
  public val name: String,
  public val distro: String,
  public val state: String,
  public val created_at: Long,
)
