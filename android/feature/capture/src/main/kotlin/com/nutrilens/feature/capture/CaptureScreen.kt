package com.nutrilens.feature.capture

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.nutrilens.core.designsystem.R as UiR
import com.nutrilens.core.designsystem.component.EmptyState
import com.nutrilens.core.designsystem.component.ErrorState
import com.nutrilens.core.designsystem.component.PrimaryButton
import com.nutrilens.core.designsystem.component.SecondaryButton
import com.nutrilens.core.designsystem.theme.Dimens
import java.io.File
import java.util.concurrent.Executor

/**
 * The camera screen.
 *
 * Declining the camera permission is a supported path, not a dead end: the
 * screen explains why the permission is wanted and offers manual logging, so a
 * user who never grants it still has a working product.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CaptureRoute(
    onImageCaptured: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CaptureViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    LaunchedEffect(uiState.capturedImagePath) {
        uiState.capturedImagePath?.let { path ->
            onImageCaptured(path)
            viewModel.onNavigationHandled()
        }
    }

    if (!cameraPermission.status.isGranted) {
        CameraPermissionScreen(
            onGrant = cameraPermission::launchPermissionRequest,
            onLogManually = onCancel,
            modifier = modifier,
        )
        return
    }

    CameraScreen(
        uiState = uiState,
        newCaptureFile = viewModel::newCaptureFile,
        onCaptured = viewModel::onImageCaptured,
        onCaptureFailed = viewModel::onCaptureFailed,
        onErrorDismissed = viewModel::onErrorDismissed,
        modifier = modifier,
    )
}

@Composable
private fun CameraPermissionScreen(
    onGrant: () -> Unit,
    onLogManually: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.spaceLarge),
        verticalArrangement = Arrangement.Center,
    ) {
        EmptyState(
            title = stringResource(UiR.string.capture_permission_title),
            description = stringResource(UiR.string.capture_permission_body),
        )
        PrimaryButton(
            text = stringResource(UiR.string.capture_permission_grant),
            onClick = onGrant,
            modifier = Modifier.padding(top = Dimens.spaceMedium),
        )
        SecondaryButton(
            text = stringResource(UiR.string.capture_log_manually),
            onClick = onLogManually,
            modifier = Modifier.padding(top = Dimens.spaceSmall),
        )
    }
}

@Composable
private fun CameraScreen(
    uiState: CaptureUiState,
    newCaptureFile: () -> File,
    onCaptured: (File) -> Unit,
    onCaptureFailed: () -> Unit,
    onErrorDismissed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor: Executor = remember(context) { ContextCompat.getMainExecutor(context) }

    val imageCapture = remember {
        ImageCapture.Builder()
            // Latency matters more than the last few percent of quality here:
            // a meal photo is downscaled to 1440 px anyway.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    Box(modifier = modifier.fillMaxSize()) {
        CameraPreviewSurface(
            imageCapture = imageCapture,
            lifecycleOwner = lifecycleOwner,
            modifier = Modifier.fillMaxSize(),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(Dimens.spaceLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceMedium),
        ) {
            Text(
                text = stringResource(UiR.string.capture_instruction),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(UiR.string.capture_hint_reference),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            uiState.error?.let {
                ErrorState(
                    message = stringResource(UiR.string.capture_error),
                    retryLabel = stringResource(UiR.string.error_generic_retry),
                    onRetry = onErrorDismissed,
                )
            }

            val shutterDescription = stringResource(
                UiR.string.capture_shutter_content_description,
            )
            FloatingActionButton(
                onClick = {
                    if (uiState.isProcessing) return@FloatingActionButton
                    val target = newCaptureFile()
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(target).build(),
                        executor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(
                                output: ImageCapture.OutputFileResults,
                            ) = onCaptured(target)

                            override fun onError(exception: ImageCaptureException) {
                                target.delete()
                                onCaptureFailed()
                            }
                        },
                    )
                },
                modifier = Modifier
                    .size(Dimens.cameraShutterSize)
                    .semantics { contentDescription = shutterDescription },
            ) {
                Icon(imageVector = Icons.Filled.PhotoCamera, contentDescription = null)
            }
        }
    }
}

/**
 * The viewfinder.
 *
 * The camera provider is unbound in [DisposableEffect] cleanup: leaving it
 * bound would hold the camera open after the screen is gone, which blocks every
 * other app on the device from using it.
 */
@Composable
private fun CameraPreviewSurface(
    imageCapture: ImageCapture,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener(
            {
                provider = providerFuture.get().also { cameraProvider ->
                    val preview = CameraPreview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    cameraProvider.unbindAll()
                    runCatching {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture,
                        )
                    }
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose { provider?.unbindAll() }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}
