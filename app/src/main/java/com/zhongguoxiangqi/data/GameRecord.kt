package com.zhongguoxiangqi.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow

/**
 * 对局记录实体.
 */
@Entity(tableName = "games")
@TypeConverters(Converters::class)
data class GameRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 开始时间(epoch ms) */
    @ColumnInfo(name = "start_time") val startTime: Long,
    /** 结束时间 */
    @ColumnInfo(name = "end_time") val endTime: Long,
    /** 玩家执子 0=红 1=黑 */
    @ColumnInfo(name = "player_side") val playerSide: Int,
    /** 难度 1-10 */
    @ColumnInfo(name = "difficulty") val difficulty: Int,
    /** 引擎Elo */
    @ColumnInfo(name = "engine_elo") val engineElo: Int,
    /** 对局结果: 1=玩家胜, 0=和, -1=玩家负 */
    @ColumnInfo(name = "result") val result: Int,
    /** 玩家评分变化 */
    @ColumnInfo(name = "rating_delta") val ratingDelta: Int,
    /** 玩家评分 */
    @ColumnInfo(name = "player_rating") val playerRating: Int,
    /** 走法列表 (UCI 字符串列表) */
    @ColumnInfo(name = "moves") val moves: List<String>,
    /** 起始 FEN */
    @ColumnInfo(name = "initial_fen") val initialFen: String,
    /** 结束原因 */
    @ColumnInfo(name = "end_reason") val endReason: String,
    /** 棋谱注释(可包含中文着法) */
    @ColumnInfo(name = "notation") val notation: String = ""
)

class Converters {
    @TypeConverter fun fromStringList(value: List<String>): String = value.joinToString("|")
    @TypeConverter fun toStringList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split("|")
}

@Dao
interface GameRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(game: GameRecord): Long

    @Query("SELECT * FROM games ORDER BY start_time DESC")
    fun observeAll(): Flow<List<GameRecord>>

    @Query("SELECT * FROM games ORDER BY start_time DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<GameRecord>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getById(id: Long): GameRecord?

    @Query("SELECT COUNT(*) FROM games")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM games WHERE result = 1")
    suspend fun winsCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE result = 0")
    suspend fun drawsCount(): Int

    @Query("SELECT COUNT(*) FROM games WHERE result = -1")
    suspend fun lossesCount(): Int

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM games")
    suspend fun clear()
}

@Database(
    entities = [GameRecord::class],
    version = 1,
    exportSchema = false
)
abstract class GameDatabase : RoomDatabase() {
    abstract fun gameRecordDao(): GameRecordDao
}