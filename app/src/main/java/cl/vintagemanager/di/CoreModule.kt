package cl.vintagemanager.di

import android.content.Context
import cl.vintagemanager.core.VintageCore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    /**
     * Núcleo Rust como singleton. La ruta de la base SQLite la decide Kotlin
     * (context.filesDir) y se entrega al núcleo al inicializarlo.
     */
    @Provides
    @Singleton
    fun provideVintageCore(@ApplicationContext context: Context): VintageCore {
        val db = File(context.filesDir, "vintage_manager.db")
        return VintageCore(db.absolutePath)
    }
}
