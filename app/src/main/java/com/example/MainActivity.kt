package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.export.ExportManager
import com.example.ui.components.SafetyDisclaimerDialog
import com.example.ui.screens.ChartsScreen
import com.example.ui.screens.GasesScreen
import com.example.ui.screens.PlanScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.ExtremeDecoTheme
import com.example.ui.viewmodel.PlannerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Exact requested tab order: 1st Settings, 2nd Planner, 3rd Gases, 4th Charts
private enum class AppDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
    PLANNER("planner", R.string.nav_planner, Icons.Filled.Explore, Icons.Outlined.Explore),
    GASES("gases", R.string.nav_gases, Icons.Filled.LocalGasStation, Icons.Outlined.LocalGasStation),
    CHARTS("charts", R.string.nav_charts, Icons.Filled.Analytics, Icons.Outlined.Analytics)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExtremeDecoApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtremeDecoApp(viewModel: PlannerViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var currentDest by remember { mutableStateOf(AppDestination.SETTINGS) }
    var showDisclaimerModal by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Back press on secondary tabs returns cleanly to Tab 1 (Settings)
    BackHandler(enabled = currentDest != AppDestination.SETTINGS) {
        currentDest = AppDestination.SETTINGS
    }

    LaunchedEffect(state.snackbarMessage) {
        state.snackbarMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbar()
        }
    }

    // Zero-permission SAF launchers for PDF & CSV export (offloaded to Dispatchers.IO)
    val pdfCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        val dayRes = state.dayResult
        val activeIdx = state.activeDiveIndex
        if (uri != null && dayRes != null && !isExporting) {
            isExporting = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ExportManager.buildPdfBytes(dayRes, activeIdx)
                        ExportManager.writeBytesToUri(context, uri, bytes)
                    }.getOrDefault(false)
                }
                isExporting = false
                viewModel.showMessage(if (ok) "PDF dive slate saved successfully." else "Failed to write PDF file.")
            }
        }
    }

    val csvCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        val dayRes = state.dayResult
        val activeIdx = state.activeDiveIndex
        if (uri != null && dayRes != null && !isExporting) {
            isExporting = true
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val csv = ExportManager.buildCsvContent(dayRes, activeIdx)
                        ExportManager.writeBytesToUri(context, uri, csv.toByteArray(Charsets.UTF_8))
                    }.getOrDefault(false)
                }
                isExporting = false
                viewModel.showMessage(if (ok) "CSV dive telemetry saved successfully." else "Failed to write CSV file.")
            }
        }
    }

    fun timestampTag(): String = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())

    val triggerSavePdf = {
        runCatching {
            pdfCreateLauncher.launch("Extreme_DECO_v1.27.2_Dive${state.activeDiveIndex + 1}_${timestampTag()}.pdf")
        }.onFailure {
            viewModel.showMessage("No document provider available to save PDF.")
        }
        Unit
    }
    val triggerSaveCsv = {
        runCatching {
            csvCreateLauncher.launch("Extreme_DECO_v1.27.2_Dive${state.activeDiveIndex + 1}_${timestampTag()}.csv")
        }.onFailure {
            viewModel.showMessage("No document provider available to save CSV.")
        }
        Unit
    }
    val triggerSharePdf = {
        val dayRes = state.dayResult
        val activeIdx = state.activeDiveIndex
        if (dayRes != null && !isExporting) {
            isExporting = true
            scope.launch {
                val intent = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = ExportManager.buildPdfBytes(dayRes, activeIdx)
                        ExportManager.createShareIntent(
                            context,
                            "Extreme_DECO_v1.27.2_Dive${activeIdx + 1}.pdf",
                            "application/pdf",
                            bytes
                        )
                    }.getOrNull()
                }
                isExporting = false
                if (intent != null) {
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "Share PDF Dive Slate"))
                    }.onFailure {
                        viewModel.showMessage("No app available to share PDF file.")
                    }
                } else {
                    viewModel.showMessage("Unable to prepare PDF file for sharing.")
                }
            }
        }
    }
    val triggerShareCsv = {
        val dayRes = state.dayResult
        val activeIdx = state.activeDiveIndex
        if (dayRes != null && !isExporting) {
            isExporting = true
            scope.launch {
                val intent = withContext(Dispatchers.IO) {
                    runCatching {
                        val csv = ExportManager.buildCsvContent(dayRes, activeIdx)
                        ExportManager.createShareIntent(
                            context,
                            "Extreme_DECO_v1.27.2_Dive${activeIdx + 1}.csv",
                            "text/csv",
                            csv.toByteArray(Charsets.UTF_8)
                        )
                    }.getOrNull()
                }
                isExporting = false
                if (intent != null) {
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "Share CSV Telemetry"))
                    }.onFailure {
                        viewModel.showMessage("No app available to share CSV file.")
                    }
                } else {
                    viewModel.showMessage("Unable to prepare CSV file for sharing.")
                }
            }
        }
    }

    ExtremeDecoTheme(darkTheme = state.input.general.darkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                topBar = {
                    Column {
                        TopAppBar(
                            title = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = stringResource(R.string.app_brand_title),
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.app_brand_subtitle),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        CompositionLocalProvider(
                                            LocalMinimumInteractiveComponentSize provides Dp.Unspecified
                                        ) {
                                            Surface(
                                                onClick = { showAboutDialog = true },
                                                shape = RoundedCornerShape(10.dp),
                                                color = MaterialTheme.colorScheme.secondaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                border = BorderStroke(
                                                    width = 0.75.dp,
                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
                                                ),
                                                modifier = Modifier.testTag("top_about_button")
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.Info,
                                                        contentDescription = stringResource(R.string.about_button),
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Text(
                                                        text = stringResource(R.string.about_button),
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 10.5.sp,
                                                            fontWeight = FontWeight.SemiBold
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                    }
                },
                bottomBar = {
                    if (!useRail) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                AppDestination.entries.forEach { dest ->
                                    val selected = currentDest == dest
                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { currentDest = dest },
                                        icon = {
                                            Icon(
                                                imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                                contentDescription = stringResource(dest.labelRes)
                                            )
                                        },
                                        label = { Text(stringResource(dest.labelRes)) },
                                        modifier = Modifier.testTag("nav_${dest.route}")
                                    )
                                }
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (useRail) {
                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                            AppDestination.entries.forEach { dest ->
                                val selected = currentDest == dest
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { currentDest = dest },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = { Text(stringResource(dest.labelRes)) },
                                    modifier = Modifier.testTag("nav_${dest.route}")
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        when (currentDest) {
                            AppDestination.SETTINGS -> SetupScreen(
                                state = state,
                                viewModel = viewModel,
                                onShowDisclaimer = { showDisclaimerModal = true },
                                onNavigateToPlanner = { currentDest = AppDestination.PLANNER }
                            )
                            AppDestination.PLANNER -> PlanScreen(
                                state = state,
                                viewModel = viewModel,
                                onExportPdf = triggerSavePdf,
                                onExportCsv = triggerSaveCsv,
                                onSharePdf = triggerSharePdf,
                                onShareCsv = triggerShareCsv,
                                onNavigateToGases = { currentDest = AppDestination.GASES }
                            )
                            AppDestination.GASES -> GasesScreen(
                                state = state,
                                viewModel = viewModel
                            )
                            AppDestination.CHARTS -> ChartsScreen(
                                state = state,
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }

            // Mandatory First-Run Safety Disclaimer with single "I understand" button
            if (state.disclaimerLoaded && !state.disclaimerAccepted) {
                SafetyDisclaimerDialog(
                    isFirstRun = true,
                    onAccept = { viewModel.acceptDisclaimer() }
                )
            } else if (showDisclaimerModal) {
                SafetyDisclaimerDialog(
                    isFirstRun = false,
                    onAccept = { showDisclaimerModal = false },
                    onDismiss = { showDisclaimerModal = false }
                )
            }

            if (showAboutDialog) {
                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    icon = {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    title = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.app_brand_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = stringResource(R.string.app_brand_subtitle),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    text = {
                        Text(
                            text = stringResource(R.string.about_message),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                runCatching {
                                    val mailIntent = Intent(Intent.ACTION_SENDTO).apply {
                                        data = Uri.parse("mailto:RamyRedaM@gmail.com")
                                        putExtra(Intent.EXTRA_SUBJECT, "Extreme DECO Planner Proposal")
                                    }
                                    context.startActivity(mailIntent)
                                }.onFailure {
                                    viewModel.showMessage("Contact: RamyRedaM@gmail.com")
                                }
                            },
                            modifier = Modifier.testTag("about_email_button")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Email,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(5.dp))
                            Text("Email")
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { showAboutDialog = false },
                            modifier = Modifier.testTag("about_close_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Close")
                        }
                    }
                )
            }
        }
    }
}
