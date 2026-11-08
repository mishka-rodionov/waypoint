package waypoint.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import waypoint.app.map.TileCache
import waypoint.app.ui.LegListScreen
import waypoint.app.ui.LegScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WaypointApp() }
    }
}

private val colors = lightColorScheme(
    primary = Color(0xFFB01783),
    secondary = Color(0xFF1E5AFF),
)

@Composable
fun WaypointApp(vm: GameViewModel = viewModel()) {
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            when (val state = vm.packState) {
                PackState.Loading -> Centered { CircularProgressIndicator() }
                PackState.Missing -> Centered {
                    Text("В приложении нет пака карты. Соберите его: ./gradlew :tools:buildPack, затем пересоберите APK.")
                }
                is PackState.Failed -> Centered { Text("Ошибка: ${state.message}", color = MaterialTheme.colorScheme.error) }
                is PackState.Ready -> {
                    val context = LocalContext.current
                    val scope = rememberCoroutineScope()
                    // Кэш тайлов общий для всех связок пака.
                    val tiles = remember(state.pack) { TileCache(context.assets, state.pack.dir, state.pack.meta.tiles, scope) }
                    val session = vm.session
                    if (session == null) {
                        LegListScreen(
                            pack = state.pack,
                            packNames = vm.packNames,
                            onPack = vm::selectPack,
                            modelReady = vm.modelReady,
                            bestScores = vm.bestScores,
                            mode = vm.mode,
                            onMode = { vm.mode = it },
                            memorySeconds = vm.memorySeconds,
                            onMemorySeconds = { vm.memorySeconds = it },
                            onOpen = vm::openLeg,
                        )
                    } else {
                        LegScreen(vm, session, tiles, onBack = vm::closeLeg)
                    }
                }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}
