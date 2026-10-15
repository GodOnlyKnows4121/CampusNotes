package cn.campus.notes
import android.app.Application
import androidx.room.Room
import cn.campus.notes.data.*

class CampusApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, CampusDatabase::class.java, "campus.db").addMigrations(CampusDatabase.MIGRATION_1_2, CampusDatabase.MIGRATION_2_3, CampusDatabase.MIGRATION_3_4, CampusDatabase.MIGRATION_4_5).build() }
    val repository by lazy { LocalRepository(this, database) }
    val settings by lazy { SettingsStore(this) }
    val contentProcessor by lazy { cn.campus.notes.integration.ContentProcessor(this, repository, settings) }
}
