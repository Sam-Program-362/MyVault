package com.myvault.app

import android.app.Application
import com.myvault.app.data.AppDatabase
import com.myvault.app.data.BackupManager
import com.myvault.app.data.MediaFiles
import com.myvault.app.data.SettingsRepository

class MyVaultApplication : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val media: MediaFiles by lazy { MediaFiles(this) }
    val backups: BackupManager by lazy { BackupManager(this, database, settings, media) }
}
