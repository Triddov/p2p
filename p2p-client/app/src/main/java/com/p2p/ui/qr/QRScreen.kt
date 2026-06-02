package com.p2p.ui.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.p2p.data.repository.AuthRepository
import com.p2p.ui.contacts.ContactsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyQRScreen(
    onBack: () -> Unit,
    viewModel: ContactsViewModel = hiltViewModel(),
    authRepository: AuthRepository
) {
    val profile = authRepository.getLocalProfileFlow().collectAsState(initial = null)

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(profile.value) {
        profile.value?.let { p ->
            val qrContent = viewModel.generateMyQRCode(p.userId, p.username)
            qrBitmap = generateQRCode(qrContent)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My QR Code") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Share this QR code with others to add you as a contact",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            qrBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "My QR Code",
                    modifier = Modifier.size(300.dp)
                )
            } ?: CircularProgressIndicator()

            Spacer(modifier = Modifier.height(32.dp))

            profile.value?.let { p ->
                Text(
                    text = "Username: ${p.username ?: "Not set"}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "ID: ${p.userId.take(8)}...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private suspend fun generateQRCode(content: String): Bitmap = withContext(Dispatchers.Default) {
    val writer = QRCodeWriter()
    val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, 512, 512)

    val width = bitMatrix.width
    val height = bitMatrix.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

    for (x in 0 until width) {
        for (y in 0 until height) {
            bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }

    bitmap
}