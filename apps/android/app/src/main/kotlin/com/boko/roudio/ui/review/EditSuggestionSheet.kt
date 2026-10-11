package com.boko.roudio.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.api.models.ReviewView
import com.boko.roudio.ui.search.positionText
import com.boko.roudio.ui.theme.RoudioRadius
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText

/**
 * 10c: reword a suggestion and accept it in one step. The design's due date isn't kept by the
 * server yet (ReviewChange has only state and text), so the sheet leaves it out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditSuggestionSheet(item: ReviewView, onSave: (String) -> Unit, onCancel: () -> Unit) {
    val c = RoudioTheme.colors
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by rememberSaveable(item.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalBottomSheet(
        onDismissRequest = onCancel,
        sheetState = sheet,
        containerColor = c.sheet,
        shape = RoundedCornerShape(topStart = RoudioRadius.Sheet, topEnd = RoudioRadius.Sheet),
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.review_edit_title),
                    style = roudioText(22.sp, 28.sp, FontWeight.SemiBold),
                    color = c.ink,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(
                    stringResource(R.string.review_edit_from, item.conversation.title, positionText(item.atMs)),
                    style = roudioText(13.sp, 18.sp),
                    color = c.muted,
                    maxLines = 1,
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(c.ground, RoundedCornerShape(20.dp))
                    .border(2.dp, c.ink, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(stringResource(R.string.review_edit_label), style = roudioText(13.sp, 18.sp), color = c.muted)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = roudioText(17.sp, 24.sp).copy(color = c.ink),
                    cursorBrush = SolidColor(c.ink),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(text.text) },
                    enabled = text.text.isNotBlank(),
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = c.primary,
                        contentColor = c.onPrimary,
                        disabledContainerColor = c.primary.copy(alpha = 0.5f),
                        disabledContentColor = c.onPrimary,
                    ),
                ) {
                    Text(stringResource(R.string.review_save_accept), style = roudioText(16.sp, 20.sp, FontWeight.SemiBold))
                }
                Button(
                    onClick = onCancel,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = c.raised, contentColor = c.ink),
                ) {
                    Text(stringResource(R.string.cancel), style = roudioText(16.sp, 20.sp, FontWeight.SemiBold))
                }
            }
        }
    }
}
