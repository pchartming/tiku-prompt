package io.github.pchartming.tikuprompt

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.pchartming.tikuprompt.data.AppRepository
import io.github.pchartming.tikuprompt.ui.TikuScreen
import io.github.pchartming.tikuprompt.ui.TikuViewModel
import io.github.pchartming.tikuprompt.ui.theme.TikuPromptTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = AppRepository(applicationContext)
        setContent {
            TikuPromptTheme {
                val viewModel: TikuViewModel = viewModel(
                    factory = TikuViewModel.Factory(repository),
                )
                TikuScreen(viewModel = viewModel)
            }
        }
    }
}