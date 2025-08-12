    package com.example.smsmaptracker

    import android.Manifest
    import android.app.DatePickerDialog
    import android.app.TimePickerDialog
    import android.content.pm.PackageManager
    import android.os.Bundle
    import androidx.activity.ComponentActivity
    import androidx.activity.compose.setContent
    import androidx.activity.result.contract.ActivityResultContracts
    import androidx.compose.foundation.layout.*
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.FilterList
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.platform.LocalContext
    import androidx.compose.ui.unit.dp
    import androidx.core.content.ContextCompat
    import org.mapsforge.map.android.graphics.AndroidGraphicFactory
    import androidx.core.view.WindowCompat
    import java.util.Calendar
    import androidx.compose.material.icons.filled.Navigation
    import android.content.Intent
    import android.widget.Toast
    import android.os.Handler
    import android.os.Looper

    class MainActivity : ComponentActivity() {

        private val requestSmsPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
                // Handle permission result if needed
            }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)

            WindowCompat.setDecorFitsSystemWindows(window, false)

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // API 30+
                window.insetsController?.let { controller ->
                    controller.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                // For API 21 to 29, use deprecated systemUiVisibility flags
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
            }

            AndroidGraphicFactory.createInstance(application)

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
                != PackageManager.PERMISSION_GRANTED) {
                requestSmsPermissionLauncher.launch(Manifest.permission.READ_SMS)
            }

            launchTermux()

            Handler(Looper.getMainLooper()).postDelayed({
                bringAppToFront()
            }, 1000L)

            setContent {
                DateTimePickerWithMap()
            }
        }


        private fun launchTermux() {
            val launchIntent = packageManager.getLaunchIntentForPackage("com.termux")
            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                Toast.makeText(this, "Termux app not installed. Please install Termux to continue.", Toast.LENGTH_LONG).show()
            }
        }

        private fun bringAppToFront() {
            val intent = Intent(this, MainActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }

        @Composable
        fun DateTimePickerWithMap() {
            var startYear by remember { mutableIntStateOf(2025) }
            var startMonth by remember { mutableIntStateOf(8) }
            var startDay by remember { mutableIntStateOf(4) }
            var startHour by remember { mutableIntStateOf(13) }
            var startMinute by remember { mutableIntStateOf(0) }
    
            var endYear by remember { mutableIntStateOf(2025) }
            var endMonth by remember { mutableIntStateOf(8) }
            var endDay by remember { mutableIntStateOf(4) }
            var endHour by remember { mutableIntStateOf(15) }
            var endMinute by remember { mutableIntStateOf(30) }
    
            val context = LocalContext.current
            var showFilterDialog by remember { mutableStateOf(false) }
    
            // New state to trigger route drawing
            var drawRouteTrigger by remember { mutableStateOf(false) }
    
            fun showDatePicker(
                initialYear: Int,
                initialMonth: Int,
                initialDay: Int,
                onDateSelected: (year: Int, month: Int, day: Int) -> Unit
            ) {
                DatePickerDialog(
                    context,
                    { _, year, month, dayOfMonth ->
                        onDateSelected(year, month + 1, dayOfMonth)
                    },
                    initialYear,
                    initialMonth - 1,
                    initialDay
                ).show()
            }
    
            fun showTimePicker(
                initialHour: Int,
                initialMinute: Int,
                onTimeSelected: (hour: Int, minute: Int) -> Unit
            ) {
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        onTimeSelected(hourOfDay, minute)
                    },
                    initialHour,
                    initialMinute,
                    true
                ).show()
            }
    
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        "Start: $startYear-${startMonth.toString().padStart(2, '0')}-${
                            startDay.toString().padStart(2, '0')
                        } ${startHour.toString().padStart(2, '0')}:${
                            startMinute.toString().padStart(2, '0')
                        }",
                        style = MaterialTheme.typography.bodyLarge
                    )
    
                    Text(
                        "End: $endYear-${endMonth.toString().padStart(2, '0')}-${
                            endDay.toString().padStart(2, '0')
                        } ${endHour.toString().padStart(2, '0')}:${
                            endMinute.toString().padStart(2, '0')
                        }",
                        style = MaterialTheme.typography.bodyLarge
                    )
    
                    Spacer(modifier = Modifier.height(8.dp))
    
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        key(
                            startYear, startMonth, startDay, startHour, startMinute,
                            endYear, endMonth, endDay, endHour, endMinute,
                        ) {
                            MapsforgeMap(
                                startYear = startYear,
                                startMonth = startMonth,
                                startDay = startDay,
                                startHour = startHour,
                                startMinute = startMinute,
                                endYear = endYear,
                                endMonth = endMonth,
                                endDay = endDay,
                                endHour = endHour,
                                endMinute = endMinute,
                                drawRouteTrigger = drawRouteTrigger
                            )
                        }
                    }
                }
    
                // Existing filter button
                FloatingActionButton(
                    onClick = { showFilterDialog = true },
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 0.dp, bottom = 50.dp)
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = "Open Filters")
                }
    
                // New FloatingActionButton for route drawing (same style)
                FloatingActionButton(
                    onClick = {
                        drawRouteTrigger = !drawRouteTrigger  // toggle to trigger recomposition and route redraw
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 0.dp, bottom = 50.dp)
                ) {
                    Icon(Icons.Filled.Navigation, contentDescription = "Update Routes")
                }
    
                if (showFilterDialog) {
                    AlertDialog(
                        onDismissRequest = { showFilterDialog = false },
                        title = { Text("Select Start Date & Time") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    
                                Button(onClick = {
                                    val now = Calendar.getInstance()
                                    startYear = now.get(Calendar.YEAR)
                                    startMonth = now.get(Calendar.MONTH) + 1
                                    startDay = now.get(Calendar.DAY_OF_MONTH)
                                    startHour = 0
                                    startMinute = 0
    
                                    endYear = startYear
                                    endMonth = startMonth
                                    endDay = startDay
                                    endHour = 23
                                    endMinute = 59
                                }) {
                                    Text("Use Today (00:00 to 23:59)")
                                }
    
                                Spacer(Modifier.height(8.dp))
    
                                Text("Select Start Date & Time")
                                Button(onClick = {
                                    showDatePicker(startYear, startMonth, startDay) { y, m, d ->
                                        startYear = y; startMonth = m; startDay = d
                                    }
                                }) {
                                    Text("Start Date: $startYear-${startMonth.toString().padStart(2, '0')}-${
                                        startDay.toString().padStart(2, '0')
                                    }")
                                }
                                Button(onClick = {
                                    showTimePicker(startHour, startMinute) { h, m ->
                                        startHour = h; startMinute = m
                                    }
                                }) {
                                    Text("Start Time: ${startHour.toString().padStart(2, '0')}:${startMinute.toString().padStart(2, '0')}")
                                }
    
                                Spacer(Modifier.height(8.dp))
                                Text("Select End Date & Time")
                                Button(onClick = {
                                    showDatePicker(endYear, endMonth, endDay) { y, m, d ->
                                        endYear = y; endMonth = m; endDay = d
                                    }
                                }) {
                                    Text("End Date: $endYear-${endMonth.toString().padStart(2, '0')}-${
                                        endDay.toString().padStart(2, '0')
                                    }")
                                }
                                Button(onClick = {
                                    showTimePicker(endHour, endMinute) { h, m ->
                                        endHour = h; endMinute = m
                                    }
                                }) {
                                    Text("End Time: ${endHour.toString().padStart(2, '0')}:${endMinute.toString().padStart(2, '0')}")
                                }
                            }
                        },
                        confirmButton = {
                            Button(onClick = { showFilterDialog = false }) {
                                Text("Close")
                            }
                        }
                    )
                }
            }
        }


        override fun onDestroy() {
            super.onDestroy()
            AndroidGraphicFactory.clearResourceMemoryCache()
        }
    }
