package com.frostbyte.gamebooster.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.frostbyte.gamebooster.ui.theme.Frost

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Frost.Card),
        border = BorderStroke(1.dp, Frost.CardEdge),
    ) { content() }
}

@Composable
fun StatTile(title: String, value: String, sub: String, modifier: Modifier = Modifier) {
    GlassCard(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Frost.Muted, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Frost.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Frost.Cyan, fontSize = 12.sp)
        }
    }
}

@Composable
fun GameIcon(drawable: Drawable?, size: Int = 48) {
    val bitmap = remember(drawable) { drawable?.toBitmap(96, 96)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp).clip(RoundedCornerShape(12.dp)))
    } else {
        Spacer(Modifier.width(size.dp))
    }
}

@Composable
fun ComingSoonScreen(title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, color = Frost.Text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(text, color = Frost.Muted, fontSize = 14.sp)
    }
}

@Composable
fun SectionTitle(text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = Frost.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ToolCard(title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    GlassCard(modifier.clickable { onClick() }) {
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Frost.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = Frost.Muted, fontSize = 11.sp)
        }
    }
}
