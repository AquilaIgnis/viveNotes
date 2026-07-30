package st.unamedtba

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import st.unamedtba.ui.NotesApp
import st.unamedtba.ui.NotesViewModel
import st.unamedtba.ui.theme.UnamedTbaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val repository = (application as NotesApplication).repository
        val viewModel = ViewModelProvider(
            this,
            NotesViewModel.factory(repository),
        )[NotesViewModel::class.java]

        setContent {
            UnamedTbaTheme {
                NotesApp(viewModel)
            }
        }
    }
}
