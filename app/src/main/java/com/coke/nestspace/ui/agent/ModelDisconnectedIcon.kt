package com.coke.nestspace.ui.agent

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.coke.nestspace.R
import com.coke.nestspace.i18n.AppLanguage

@Composable
internal fun ModelDisconnectedIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current
) {
    Icon(
        painter = painterResource(R.drawable.ic_model_disconnected),
        contentDescription = AppLanguage.tr(
            "大模型尚未连接",
            "Model is not connected"
        ),
        modifier = modifier,
        tint = tint
    )
}
