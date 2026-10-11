package it.ehimacchina.obd

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** Un viaggio: dal motore acceso allo spegnimento. [fuelL] è il consumo stimato SENZA taratura. */
@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val km: Double,
    val fuelL: Double,
    val maxSpeed: Double,
)

/** Campione dei dati, uno ogni ~2 secondi durante un viaggio. */
@Entity(tableName = "samples", indices = [Index("tripId")])
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val tMs: Long,
    val rpm: Double?,
    val speed: Double?,
    val coolant: Double?,
    val load: Double?,
    val mapKpa: Double?,
    val battery: Double?,
    val lph: Double?,
)

/** Codice errore visto almeno una volta. [clearedMs] != null = cancellato dall'app (o sparito). */
@Entity(tableName = "dtc")
data class DtcEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val code: String,
    /** "memorizzato" o "pendente" */
    val kind: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val clearedMs: Long?,
    /** Freeze frame in testo, vedi [FreezeFrame.toText] */
    val freeze: String?,
)

/** Rifornimento inserito a mano: litri messi e km fatti dal pieno precedente. */
@Entity(tableName = "refuels")
data class RefuelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateMs: Long,
    val liters: Double,
    val km: Double,
)

/** Evento in attesa di essere inviato al server (JSON già pronto). */
@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdMs: Long,
    val json: String,
)

/** Rapporto di un test di guida diagnostico, già in testo (si condivide e si legge così). */
@Entity(tableName = "drive_reports")
data class DriveReportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

@Dao
interface ObdDao {
    @Insert suspend fun insertOutbox(e: OutboxEntity): Long
    @Query("SELECT * FROM outbox ORDER BY id LIMIT :limit") suspend fun outboxBatch(limit: Int): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id IN (:ids)") suspend fun deleteOutbox(ids: List<Long>)
    @Query("SELECT COUNT(*) FROM outbox") fun outboxCount(): Flow<Int>
    /** Tiene solo gli ultimi [keep] eventi: se il server resta irraggiungibile a lungo la coda non cresce all'infinito */
    @Query("DELETE FROM outbox WHERE id <= (SELECT id FROM outbox ORDER BY id DESC LIMIT 1 OFFSET :keep)")
    suspend fun trimOutbox(keep: Int)

    @Insert suspend fun insertDriveReport(r: DriveReportEntity): Long
    @Query("SELECT * FROM drive_reports ORDER BY startMs DESC") fun driveReportsFlow(): Flow<List<DriveReportEntity>>

    @Insert suspend fun insertTrip(t: TripEntity): Long
    @Update suspend fun updateTrip(t: TripEntity)
    @Query("SELECT * FROM trips ORDER BY startMs DESC") fun tripsFlow(): Flow<List<TripEntity>>
    @Query("SELECT * FROM trips ORDER BY startMs") suspend fun allTrips(): List<TripEntity>

    @Query("DELETE FROM trips WHERE id = :id") suspend fun deleteTrip(id: Long)
    @Query("DELETE FROM samples WHERE tripId = :id") suspend fun deleteSamples(id: Long)

    @Insert suspend fun insertSample(s: SampleEntity)

    @Insert suspend fun insertDtc(d: DtcEntity): Long
    @Update suspend fun updateDtc(d: DtcEntity)
    @Query("SELECT * FROM dtc WHERE code = :code AND clearedMs IS NULL LIMIT 1") suspend fun openDtc(code: String): DtcEntity?
    @Query("SELECT * FROM dtc WHERE clearedMs IS NULL") suspend fun openDtcs(): List<DtcEntity>
    @Query("SELECT * FROM dtc") suspend fun allDtcs(): List<DtcEntity>
    @Query("SELECT * FROM drive_reports") suspend fun allDriveReports(): List<DriveReportEntity>
    @Query("SELECT * FROM dtc ORDER BY lastSeenMs DESC") fun dtcFlow(): Flow<List<DtcEntity>>

    @Insert suspend fun insertRefuel(r: RefuelEntity): Long
    @Query("DELETE FROM refuels WHERE id = :id") suspend fun deleteRefuel(id: Long)
    @Query("SELECT * FROM refuels ORDER BY dateMs DESC") fun refuelsFlow(): Flow<List<RefuelEntity>>
    @Query("SELECT * FROM refuels ORDER BY dateMs") suspend fun allRefuels(): List<RefuelEntity>
}

@Database(
    entities = [TripEntity::class, SampleEntity::class, DtcEntity::class, RefuelEntity::class, DriveReportEntity::class, OutboxEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): ObdDao

    companion object {
        /** v2: tabella dei rapporti di test di guida (non tocca i dati esistenti) */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drive_reports` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, `text` TEXT NOT NULL)",
                )
            }
        }

        /** v3: coda di invio al server */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `outbox` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`createdMs` INTEGER NOT NULL, `json` TEXT NOT NULL)",
                )
            }
        }

        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "ehi-macchina.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build().also { instance = it }
        }
    }
}
