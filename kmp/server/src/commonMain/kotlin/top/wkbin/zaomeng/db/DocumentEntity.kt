package top.wkbin.zaomeng.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 文档存储实体：把原本的 JSON/二进制文件按“虚拟路径”收进 SQLite。
 *
 * 路径即主键（如 `D:\...\zaomeng-data\runs\run-x\run_manifest.json`），
 * 目录是隐式的（不存在独立行）。这样 StorageService 的路径语义完全保留，
 * 所有服务层不用改。
 */
@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey val path: String,
    val bytes: ByteArray,
    @ColumnInfo(name = "updated_at") val updatedAtMillis: Long,
)
