package app.baton.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.baton.parking.ui.BatonApp

class MainActivity : ComponentActivity() {
    private val viewModel: BatonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BatonApp(viewModel) }
    }
}
