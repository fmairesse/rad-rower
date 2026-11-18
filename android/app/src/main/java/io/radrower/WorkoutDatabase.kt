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
    val startedAt: Long,
    val startedOffsetSeconds: Int,
    val endedAt: Long? = null,
    val endedOffsetSeconds: Int? = null,
    val lastSampleAt: Long,
    val lastSampleOffsetSeconds: Int,
    val status: String = "recording",
    val partial: Boolean,
    val sampleCount: Int = 0,
    val activeMillis: Long = 0,
    val distance: Double? = null,
    val calories: Double? = null,
    val strokes: Double? = null,
    val elapsed: Double? = null,
    val paused: Double? = null,
    val averageWatts: Double? = null,
    val cadenceTotal: Double = 0.0,
    val cadenceSamples: Int = 0,
    val averageCadence: Double? = null,
    val averageSpeed: Double? = null,
    val averagePace: Double? = null,
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
    val timestamp: Long,
    val offsetSeconds: Int,
    val metricsJson: String,
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

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    abstract fun observeWorkouts(): Flow<List<Workout>>

    @Query("SELECT * FROM workouts ORDER BY startedAt DESC")
    abstract suspend fun workouts(): List<Workout>

    @Query("SELECT * FROM samples WHERE workoutId = :id ORDER BY sequence")
    abstract suspend fun samples(id: String): List<WorkoutSample>

    @Query("""
        UPDATE workouts SET status = 'interrupted', endedAt = lastSampleAt,
            endedOffsetSeconds = lastSampleOffsetSeconds
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
