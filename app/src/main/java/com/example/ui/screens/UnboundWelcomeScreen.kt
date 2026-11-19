package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val DeepBgStart = Color(0xFF041513)
private val DeepBgMid = Color(0xFF082622)
private val DeepBgEnd = Color(0xFF061B24)
private val AuroraEmerald = Color(0xFF10B981)
private val AuroraTeal = Color(0xFF0EA5A4)
private val AuroraGold = Color(0xFFF59E0B)
private val GlassSurface = Color(0xCC0D2421)
private val GlassInnerCard = Color(0x99071917)
private val GlassBorder = Color(0x4034D399)
private val AccentMint = Color(0xFF6EE7B7)
private val SoftWhite = Color(0xFFF8FAFC)
private val MutedMint = Color(0xFFCBD5E1)

@Composable
fun UnboundWelcomeScreen(
    onOpenUrlBinding: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .testTag("unbound_welcome_screen")
    ) {
        // Live fluid animated ambient tech backdrop
        AnimatedTechBackdrop(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Subtle floating top bar with access badge and Admin Settings gear
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color.White.copy(alpha = 0.08f),
                    border = BorderStroke(1.dp, GlassBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = AuroraGold,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "অ্যাডমিন প্যানেল সংযোগ আবশ্যক",
                            style = MaterialTheme.typography.labelSmall,
                            color = SoftWhite,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.10f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f))
                ) {
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("top_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                            tint = SoftWhite
                        )
                    }
                }
            }

            // Scrollable centered content card
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = GlassSurface,
                    border = BorderStroke(1.dp, GlassBorder),
                    shadowElevation = 12.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 460.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 26.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Brand Logo (tappable to open Store Setup)
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(26.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            AuroraEmerald.copy(alpha = 0.35f),
                                            AuroraGold.copy(alpha = 0.25f)
                                        )
                                    )
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = AccentMint.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(26.dp)
                                )
                                .clickable(onClick = onOpenUrlBinding)
                                .padding(6.dp)
                                .testTag("top_store_logo_button")
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_bangla_store_fg),
                                contentDescription = "Bangla Store Logo",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(20.dp))
                            )
                        }

                        // Brand Name & Primary Tag
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Bangla Store",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = SoftWhite,
                                letterSpacing = 0.4.sp,
                                textAlign = TextAlign.Center
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = AuroraEmerald.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, AuroraEmerald.copy(alpha = 0.45f))
                            ) {
                                Text(
                                    text = "A Store Management Soft",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = AccentMint,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // Company / Provider Details Card
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = GlassInnerCard,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("welcome_company_info_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Provider & Tag
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = AuroraEmerald.copy(alpha = 0.18f),
                                        modifier = Modifier.size(38.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Rounded.VerifiedUser,
                                                contentDescription = null,
                                                tint = AccentMint,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Provider: Khan Soft",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = SoftWhite
                                        )
                                        Text(
                                            text = "Man In Technology",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AuroraGold,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                                // Interactive Contact Number
                                CompanyContactRow(
                                    icon = Icons.Rounded.Call,
                                    label = "Contact",
                                    value = "01707559559",
                                    actionLabel = "কল করুন",
                                    testTag = "welcome_contact_phone_row",
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_DIAL, Uri.parse("tel:01707559559"))
                                            )
                                        }
                                    }
                                )

                                // Interactive Website Link
                                CompanyContactRow(
                                    icon = Icons.Rounded.Language,
                                    label = "Web",
                                    value = "Rkmenon.com",
                                    actionLabel = "ভিজিট",
                                    testTag = "welcome_contact_web_row",
                                    onClick = {
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse("https://rkmenon.com"))
                                            )
                                        }
                                    }
                                )
                            }
                        }

                        // Access Restriction Notice
                        Text(
                            text = "এই সফটওয়্যারটি ব্যবহার করতে আপনার স্টোরের অ্যাডমিন প্যানেলের সাথে কানেক্ট করুন। সংযোগ ছাড়া কোনো ফিচার ব্যবহার করা যাবে না।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MutedMint,
                            textAlign = TextAlign.Center,
                            lineHeight = 19.sp
                        )

                        // Primary Connect CTA (Direct URL)
                        Button(
                            onClick = onOpenUrlBinding,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AuroraEmerald,
                                contentColor = Color(0xFF022C22)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("welcome_connect_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "অ্যাডমিন প্যানেল কানেক্ট করুন (URL)",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Secondary Connect CTA (QR Code Scanner)
                        OutlinedButton(
                            onClick = onOpenQrScanner,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SoftWhite
                            ),
                            border = BorderStroke(1.2.dp, AccentMint.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 52.dp)
                                .testTag("welcome_connect_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.QrCodeScanner,
                                contentDescription = null,
                                tint = AccentMint,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "কিউআর কোড স্ক্যান করুন (Scan QR Code)",
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun CompanyContactRow(
    icon: ImageVector,
    label: String,
    value: String,
    actionLabel: String,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
            .heightIn(min = 48.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.09f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = AccentMint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MutedMint
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = SoftWhite
            )
        }
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = AuroraEmerald.copy(alpha = 0.18f)
        ) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelSmall,
                color = AccentMint,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
private fun AnimatedTechBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "welcome_bg_motion")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb_phase"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle_drift"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Deep rich emerald-slate gradient canvas
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(DeepBgStart, DeepBgMid, DeepBgEnd)
            )
        )

        // Flowing aurora orb 1 (Emerald)
        val orb1Center = Offset(
            x = w * (0.22f + 0.14f * cos(phase)),
            y = h * (0.20f + 0.08f * sin(phase))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AuroraEmerald.copy(alpha = 0.34f), Color.Transparent),
                center = orb1Center,
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = orb1Center
        )

        // Flowing aurora orb 2 (Teal)
        val orb2Center = Offset(
            x = w * (0.80f + 0.12f * sin(phase * 1.2f)),
            y = h * (0.76f + 0.10f * cos(phase))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AuroraTeal.copy(alpha = 0.30f), Color.Transparent),
                center = orb2Center,
                radius = w * 0.68f
            ),
            radius = w * 0.68f,
            center = orb2Center
        )

        // Subtle warm gold orb 3 (matching brand checkmark)
        val orb3Center = Offset(
            x = w * (0.76f - 0.15f * cos(phase * 0.8f)),
            y = h * (0.24f + 0.10f * sin(phase * 0.9f))
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(AuroraGold.copy(alpha = 0.16f), Color.Transparent),
                center = orb3Center,
                radius = w * 0.45f
            ),
            radius = w * 0.45f,
            center = orb3Center
        )

        // Subtle geometric orbital tech rings
        val center = Offset(w * 0.5f, h * 0.46f)
        val ringScale = 1f + 0.04f * sin(phase)
        drawCircle(
            color = AccentMint.copy(alpha = 0.07f),
            radius = minOf(w, h) * 0.38f * ringScale,
            center = center,
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(
            color = AuroraTeal.copy(alpha = 0.05f),
            radius = minOf(w, h) * 0.56f * (2f - ringScale),
            center = center,
            style = Stroke(width = 1.dp.toPx())
        )

        // Gently rising luminous tech particles
        val particleCount = 18
        for (i in 0 until particleCount) {
            val seedX = ((i * 37 + 13) % 100) / 100f
            val baseOffset = ((i * 53 + 7) % 100) / 100f
            val progressY = (baseOffset - drift + 1f) % 1f
            val px = w * seedX + sin(phase + i) * 14.dp.toPx()
            val py = h * progressY
            val alpha = (sin(progressY * PI).toFloat().coerceIn(0f, 1f)) * 0.32f
            val r = (2f + (i % 3) * 1.4f).dp.toPx()
            drawCircle(
                color = if (i % 4 == 0) AuroraGold.copy(alpha = alpha) else AccentMint.copy(alpha = alpha),
                radius = r,
                center = Offset(px, py)
            )
        }
    }
}
