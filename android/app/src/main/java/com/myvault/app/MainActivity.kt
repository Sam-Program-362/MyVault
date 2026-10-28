package com.myvault.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.myvault.app.ui.MyVaultApp
import com.myvault.app.ui.VaultViewModel

class MainActivity : ComponentActivity() {
    private val vault: VaultViewModel by lazy { ViewModelProvider(this)[VaultViewModel::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MyVaultApp(vault) }
    }

    override fun onStop() {
        // The app may be backgrounded while a file picker is open or the user switches apps.
        vault.savePendingDraft()
        super.onStop()
    }
}
