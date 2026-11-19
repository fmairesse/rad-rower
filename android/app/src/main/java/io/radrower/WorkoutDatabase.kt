package io.radrower

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "workouts")
internal data class Workout(
    @PrimaryKey val id: String,
    val startedAtEpochMs: Long,
    val startedOffsetS: Int,
    val endedAtEpochMs: Long? = null,
    val endedOffsetS: Int? = null,
    val lastSampleAtEpochMs: Long,
    val lastSampleOffsetS: Int,
    val status: String = "recording",
    val partial: Boolean,
    val sampleCount: Int = 0,
    val activeMs: Long = 0,
    val distanceM: Double? = null,
    val caloriesKcal: Double? = null,
    val strokesCount: Double? = null,
    val elapsedS: Double? = null,
    val cadenceTotalSpm: Double = 0.0,
    val cadenceSampleCount: Int = 0,
    val averageCadenceSpm: Double? = null,
    val averageSpeedKmh: Double? = null,
    val averagePaceSPer500M: Double? = null,
)

@Entity(
    tableName = "samples",
    primaryKeys = ["workoutId", "sequence"],
    foreignKeys = [ForeignKey(
        entity = Workout::class,
        parentColumns = ["id"],
        childColumns = ["workoutId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("workoutId")],
)
internal data class WorkoutSample(
    val workoutId: String,
    val sequence: Int,
    val timestampEpochMs: Long,
    val offsetS: Int,
    val sessionState: Int,
    val powerW: Double? = null,
    val distanceM: Double? = null,
    val resistanceLevel: Int,
    val elapsedS: Double? = null,
    val caloriesKcal: Double? = null,
    val strokesCount: Double? = null,
    val cadenceSpm: Double? = null,
    val paceSPer500M: Double? = null,
    val speedKmh: Double? = null,
    val unvalidatedMetricsJson: String? = null,
)

@Dao
internal abstract class WorkoutDao {
    @Upsert
    abstract suspend fun upsertWorkout(workout: Workout)

    @Insert
    abstract suspend fun insertSample(sample: WorkoutSample)

    @Transaction
    open suspend fun save(workout: Workout, sample: WorkoutSample?) {
        upsertWorkout(workout)
        if (sample != null) insertSample(sample)
    }

    @Query("SELECT * FROM workouts ORDER BY startedAtEpochMs DESC")
    abstract fun observeWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY startedAtEpochMs DESC")
    abstract suspend fun workouts(): List<Workout>

    @Query("SELECT * FROM samples WHERE workoutId = :id ORDER BY sequence")
    abstract suspend fun samples(id: String): List<WorkoutSample>

    @Query("""
        UPDATE workouts SET status = 'interrupted', endedAtEpochMs = lastSampleAtEpochMs,
            endedOffsetS = lastSampleOffsetS
        WHERE status = 'recording'
    """)
    abstract suspend fun recoverInterrupted()
}

@Database(entities = [Workout::class, WorkoutSample::class], version = 1, exportSchema = true)
internal abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun workouts(): WorkoutDao

    companion object {
        fun create(context: Context): WorkoutDatabase =
            Room.databaseBuilder(context.applicationContext, WorkoutDatabase::class.java, "workouts.db").build()
    }
}
