package com.jarvis.pineapple.util

import java.io.File
import java.io.IOException

/**
 * 文件写入校验工具。
 *
 * 唯一目的：杜绝“调用没抛异常就算成功”的假成功。
 * 写入后必须校验文件存在且字节长度与预期一致，才返回 true。
 */
object FileVerifier {

    /**
     * 以 UTF-8 覆盖写入文本，写入后校验文件存在且长度等于预期字节数。
     * 父目录不存在时会尝试创建；任何失败（含长度不一致）都返回 false，不抛异常。
     */
    fun writeVerifiedText(file: File, text: String): Boolean = try {
        file.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) return false
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        file.writeText(text, Charsets.UTF_8)
        file.exists() && file.length() == bytes.size.toLong()
    } catch (_: IOException) {
        false
    }
}
