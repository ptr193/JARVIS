package com.jarvis.pineapple.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlin.Any
import kotlin.Long
import kotlin.String

public class JarvisQueries(
  driver: SqlDriver,
) : TransacterImpl(driver) {
  public fun <T : Any> selectAllConversations(mapper: (
    id: String,
    title: String,
    created_at: Long,
  ) -> T): Query<T> = Query(-1_933_591_238, arrayOf("conversations"), driver, "Jarvis.sq",
      "selectAllConversations",
      "SELECT conversations.id, conversations.title, conversations.created_at FROM conversations ORDER BY created_at DESC") {
      cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getLong(2)!!
    )
  }

  public fun selectAllConversations(): Query<Conversations> = selectAllConversations { id, title,
      created_at ->
    Conversations(
      id,
      title,
      created_at
    )
  }

  public fun <T : Any> selectMessagesForConversation(conversation_id: String, mapper: (
    id: String,
    conversation_id: String,
    role: String,
    content: String,
    timestamp: Long,
  ) -> T): Query<T> = SelectMessagesForConversationQuery(conversation_id) { cursor ->
    mapper(
      cursor.getString(0)!!,
      cursor.getString(1)!!,
      cursor.getString(2)!!,
      cursor.getString(3)!!,
      cursor.getLong(4)!!
    )
  }

  public fun selectMessagesForConversation(conversation_id: String): Query<Messages> =
      selectMessagesForConversation(conversation_id) { id, conversation_id_, role, content,
      timestamp ->
    Messages(
      id,
      conversation_id_,
      role,
      content,
      timestamp
    )
  }

  public fun insertConversation(
    id: String,
    title: String,
    created_at: Long,
  ) {
    driver.execute(-894_052_021,
        """INSERT INTO conversations(id, title, created_at) VALUES (?, ?, ?)""", 3) {
          bindString(0, id)
          bindString(1, title)
          bindLong(2, created_at)
        }
    notifyQueries(-894_052_021) { emit ->
      emit("conversations")
    }
  }

  public fun insertMessage(
    id: String,
    conversation_id: String,
    role: String,
    content: String,
    timestamp: Long,
  ) {
    driver.execute(1_231_693_375,
        """INSERT INTO messages(id, conversation_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?)""",
        5) {
          bindString(0, id)
          bindString(1, conversation_id)
          bindString(2, role)
          bindString(3, content)
          bindLong(4, timestamp)
        }
    notifyQueries(1_231_693_375) { emit ->
      emit("messages")
    }
  }

  private inner class SelectMessagesForConversationQuery<out T : Any>(
    public val conversation_id: String,
    mapper: (SqlCursor) -> T,
  ) : Query<T>(mapper) {
    override fun addListener(listener: Query.Listener) {
      driver.addListener("messages", listener = listener)
    }

    override fun removeListener(listener: Query.Listener) {
      driver.removeListener("messages", listener = listener)
    }

    override fun <R> execute(mapper: (SqlCursor) -> QueryResult<R>): QueryResult<R> =
        driver.executeQuery(1_574_417_813,
        """SELECT messages.id, messages.conversation_id, messages.role, messages.content, messages.timestamp FROM messages WHERE conversation_id = ? ORDER BY timestamp ASC""",
        mapper, 1) {
      bindString(0, conversation_id)
    }

    override fun toString(): String = "Jarvis.sq:selectMessagesForConversation"
  }
}
