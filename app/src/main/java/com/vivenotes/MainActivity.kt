package com.vivenotes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import com.vivenotes.ui.NotesApp
import com.vivenotes.ui.NotesViewModel
import com.vivenotes.ui.theme.ViveNotesTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as NotesApplication
        val viewModel = ViewModelProvider(
            this,
            NotesViewModel.factory(app.repository, app.editorDefaults, app.viewSettings),
        )[NotesViewModel::class.java]

        setContent {
            ViveNotesTheme {
                NotesApp(viewModel)
            }
        }
    }
}
