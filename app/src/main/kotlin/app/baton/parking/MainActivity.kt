package app.baton.parking

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import app.baton.parking.signal.SignalSourceProvider
import app.baton.parking.ui.BatonApp

class MainActivity : ComponentActivity() {
    private val viewModel: BatonViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // debug 빌드의 확인용: `am start ... --ez baton_unverified true`로 띄우면 검증되지 않은 차량 환경처럼 동작한다.
        // 뷰모델(신호 소스)을 만들기 전에 정한다. release 빌드에서는 읽지 않는다.
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("baton_unverified", false) == true) {
            SignalSourceProvider.debugForceUnverifiedProfile = true
        }
        setContent { BatonApp(viewModel) }
    }
}
