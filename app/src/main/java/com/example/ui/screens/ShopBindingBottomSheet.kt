package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.example.R
import com.example.model.PortalConfig
import com.example.model.ShopBindingUiState
import com.example.ui.theme.parseBrandHexOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopBindingBottomSheet(
    config: PortalConfig,
    bindingState: ShopBindingUiState,
    onBindShopUrl: (String) -> Unit,
    onOpenQrScanner: () -> Unit,
    onResetToDefaultStore: () -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var shopUrlInput by rememberSaveable(config.boundStoreUrl) {
        mutableStateOf(config.boundStoreUrl.ifBlank { "" })
    }
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = {
            if (!bindingState.isLoading) onDismiss()
        },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "শপ বাইন্ডিং (Store Setup)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "URL দিয়ে অথবা QR কোড স্ক্যান করে শপ কানেক্ট করুন",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Current Connection Status Card
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (config.isCustomStoreBound) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shop_binding_current_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (config.isCustomStoreBound && config.logoUrl.isNotBlank()) {
                            SubcomposeAsyncImage(
                                model = config.logoUrl,
                                contentDescription = config.effectiveStoreDisplayName,
                                contentScale = ContentScale.Crop,
                                error = {
                                    Image(
                                        painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Image(
                                painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        if (config.isCustomStoreBound) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = config.effectiveStoreDisplayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Verified,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = config.boundStoreUrl,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val codeLabel = config.storeCode.ifBlank {
                                    config.panelId.ifBlank { "Bound Store" }
                                }
                                Text(
                                    text = "স্টোর কোড: $codeLabel",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val parsedSwatch = parseBrandHexOrNull(config.themeColorHex)
                                if (parsedSwatch != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(parsedSwatch)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "অ্যাডমিন প্যানেলের সাথে যুক্ত নয়",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "অ্যাপটি ব্যবহার করতে নিচে শপের URL দিন অথবা QR কোড স্ক্যান করে কানেক্ট করুন।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Method A: Direct Store URL Input
            OutlinedTextField(
                value = shopUrlInput,
                onValueChange = {
                    shopUrlInput = it
                    if (bindingState.errorMessage != null) onClearError()
                },
                label = { Text("শপের URL দিন (Enter Store URL)") },
                placeholder = { Text("https://banglait.net/shop/store1") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Storefront,
                        contentDescription = null
                    )
                },
                trailingIcon = {
                    if (shopUrlInput.isNotEmpty() && !bindingState.isLoading) {
                        IconButton(onClick = { shopUrlInput = "" }) {
                            Icon(
                                imageVector = Icons.Rounded.Clear,
                                contentDescription = "URL মুছুন"
                            )
                        }
                    }
                },
                singleLine = true,
                enabled = !bindingState.isLoading,
                isError = bindingState.errorMessage != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (shopUrlInput.isNotBlank() && !bindingState.isLoading) {
                            onBindShopUrl(shopUrlInput)
                        }
                    }
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("shop_binding_url_input")
            )

            Text(
                text = "বাইন্ড সম্পন্ন হলে অ্যাপ রিবুট হবে এবং শুধুমাত্র ওই শপের লগইন পোর্টাল, লোগো, ট্যাব ও কন্ট্রোল প্যানেল সেটিংস চালু হবে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!bindingState.errorMessage.isNullOrBlank()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("shop_binding_error_card")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = bindingState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (!bindingState.statusMessage.isNullOrBlank() && bindingState.errorMessage == null) {
                Text(
                    text = bindingState.statusMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = { onBindShopUrl(shopUrlInput) },
                enabled = shopUrlInput.isNotBlank() && !bindingState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("shop_binding_submit_button")
            ) {
                if (bindingState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("শপ ডেটা সিঙ্ক হচ্ছে...")
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("বাইন্ড করুন / Connect")
                }
            }

            // Method B: QR Code Scanner Fallback
            FilledTonalButton(
                onClick = onOpenQrScanner,
                enabled = !bindingState.isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .testTag("shop_binding_scan_qr_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.QrCodeScanner,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("কিউআর কোড স্ক্যান করুন (Scan QR Code)")
            }

            if (config.isCustomStoreBound) {
                HorizontalDivider()
                OutlinedButton(
                    onClick = { showUnlinkConfirmDialog = true },
                    enabled = !bindingState.isLoading,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("shop_binding_reset_button")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.LinkOff,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("শপ আনলিংক ও রিবুট করুন (Unlink Store)")
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }

    if (showUnlinkConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Rounded.LinkOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("শপ আনলিংক করবেন?") },
            text = {
                Text(
                    "\"${config.effectiveStoreDisplayName}\" থেকে অ্যাপটি আনলিংক করলে বর্তমান লগইন সেশন, ব্যাকগ্রাউন্ড GPS ও শপের ডেটা মুছে যাবে এবং অ্যাপটি রিবুট হয়ে সাধারণ ডিফল্ট পোর্টালে ফিরে যাবে।"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showUnlinkConfirmDialog = false
                        onResetToDefaultStore()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("shop_binding_confirm_unlink_button")
                ) {
                    Text("আনলিংক ও রিবুট করুন")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkConfirmDialog = false }) {
                    Text("বাতিল")
                }
            }
        )
    }
}
