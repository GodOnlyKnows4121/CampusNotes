package cn.campus.notes
import android.app.Application
import androidx.room.Room
import cn.campus.notes.data.*

class CampusApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, CampusDatabase::class.java, "campus.db").build() }
    val repository by lazy { LocalRepository(this, database) }
    val settings by lazy { SettingsStore(this) }
}
