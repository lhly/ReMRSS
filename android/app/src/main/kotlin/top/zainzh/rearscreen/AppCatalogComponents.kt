package top.zainzh.rearscreen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults

/** Shared visuals only: the caller decides how selection and filtering work. */
@Composable internal fun AppCatalogCard(
    name: String,
    detail: String?,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
    muted: Boolean = false,
    verticalPadding: Dp = 16.dp,
    icon: @Composable () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    AppCard(modifier) {
        Row(Modifier.fillMaxWidth().then(interaction).padding(horizontal = 16.dp, vertical = verticalPadding),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { icon() }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name, fontSize = 16.sp, color = if (muted) AppPalette.secondary() else AppPalette.text())
                if (!detail.isNullOrEmpty()) Text(detail, fontSize = 12.sp, color = AppPalette.secondary())
            }
            trailing()
        }
    }
}

@Composable internal fun AppCatalogSearchField(
    query: String,
    change: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
) {
    TextField(query, { if (interactive) change(it) }, label = label, singleLine = true,
        modifier = modifier.fillMaxWidth().then(if (interactive) Modifier else Modifier.focusProperties { canFocus = false }),
        colors = TextFieldDefaults.textFieldColors(labelColor = AppPalette.secondary()),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        leadingIcon = {
            Icon(painterResource(R.drawable.ic_app_catalog_search), null,
                Modifier.padding(start = 14.dp, end = 8.dp).size(20.dp), AppPalette.secondary())
        })
}
