package com.boko.roudio.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.boko.roudio.R
import com.boko.roudio.RoudioApp
import com.boko.roudio.api.models.WordChange
import com.boko.roudio.api.models.WordRequest
import com.boko.roudio.api.models.WordView
import com.boko.roudio.ui.OrganiseIcons
import com.boko.roudio.ui.setup.SmallButton
import com.boko.roudio.ui.theme.RoudioTheme
import com.boko.roudio.ui.theme.roudioText
import kotlinx.coroutines.launch

/** 15b with the server's list: loads it, and adds, confirms and removes words. */
@Composable
fun WordListRouteContent(app: RoudioApp, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<WordView>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        val loaded = fetch { app.api.organise.listWords() }
        if (loaded == null) failed = words == null else words = loaded
    }
    fun replace(word: WordView) {
        words = words.orEmpty().filterNot { it.id == word.id } + word
    }
    WordListScreen(
        words = words,
        failed = failed,
        saveFailed = saveFailed,
        onRetry = { attempt++ },
        onAdd = { term ->
            saveFailed = false
            scope.launch {
                val added = fetch { app.api.organise.addWord(WordRequest(term)) }
                if (added == null) saveFailed = true else replace(added)
            }
        },
        onKeep = { word ->
            saveFailed = false
            replace(word.copy(confirmed = true))
            scope.launch {
                val saved = fetch { app.api.organise.updateWord(word.id, WordChange(confirmed = true)) }
                if (saved == null) {
                    replace(word)
                    saveFailed = true
                } else {
                    replace(saved)
                }
            }
        },
        onRemove = { word ->
            saveFailed = false
            val before = words
            words = words.orEmpty().filterNot { it.id == word.id }
            scope.launch {
                if (!send { app.api.organise.deleteWord(word.id) }) {
                    words = before
                    saveFailed = true
                }
            }
        },
        onBack = onBack,
    )
}

/**
 * 15b Your word list: the add field, learned words to keep or drop, and the list itself.
 * Tapping a word in the list offers to remove it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WordListScreen(
    words: List<WordView>?,
    failed: Boolean,
    saveFailed: Boolean,
    onRetry: () -> Unit,
    onAdd: (String) -> Unit,
    onKeep: (WordView) -> Unit,
    onRemove: (WordView) -> Unit,
    onBack: () -> Unit,
) {
    val c = RoudioTheme.colors
    var showAll by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<WordView?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(c.ground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        BackTitleHeader(stringResource(R.string.settings_word_list), onBack)
        Text(stringResource(R.string.words_intro), style = roudioText(16.sp, 24.sp), color = c.muted)
        if (words == null) {
            if (failed) CouldNotLoad(stringResource(R.string.no_connection), onRetry) else Loading()
            return@Column
        }
        AddField(onAdd)
        if (saveFailed) Text(stringResource(R.string.words_save_failed), style = roudioText(14.sp, 20.sp), color = c.alert)
        val lists = WordListModel.partition(words)
        if (lists.toConfirm.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel(stringResource(R.string.words_learned_title))
                lists.toConfirm.forEach { LearnedWord(it, onKeep, onRemove) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.words_in_list, lists.list.size))
            if (lists.list.isEmpty()) {
                Text(stringResource(R.string.words_empty), style = roudioText(15.sp, 22.sp), color = c.muted)
            }
            val shown = if (showAll || lists.list.size <= COLLAPSED) lists.list else lists.list.take(COLLAPSED - 1)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                shown.forEach { word -> WordChip(word.term, outlined = true) { removing = word } }
                if (shown.size < lists.list.size) {
                    val more = lists.list.size - shown.size
                    WordChip(pluralStringResource(R.plurals.words_more, more, more), outlined = false) { showAll = true }
                }
            }
        }
        Text(stringResource(R.string.words_cost_note), style = roudioText(15.sp, 22.sp), color = c.muted)
    }
    removing?.let { word ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.words_remove_title, word.term)) },
            text = { Text(stringResource(R.string.words_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onRemove(word)
                }) { Text(stringResource(R.string.words_remove), color = c.alert) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun AddField(onAdd: (String) -> Unit) {
    val c = RoudioTheme.colors
    var text by rememberSaveable { mutableStateOf("") }
    val term = WordListModel.clean(text)
    val add = {
        if (term != null) {
            onAdd(term)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().height(56.dp).background(c.raised, RoundedCornerShape(28.dp)).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(stringResource(R.string.words_add_hint), style = roudioText(17.sp, 22.sp), color = c.muted)
            BasicTextField(
                value = text,
                onValueChange = { if (it.length <= WordListModel.MAX_LENGTH) text = it },
                singleLine = true,
                textStyle = roudioText(17.sp, 22.sp).copy(color = c.ink),
                cursorBrush = SolidColor(c.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SmallButton(stringResource(R.string.words_add), onClick = add, filled = true)
    }
}

/** A word learned from the owner's fixes: dashed until kept. */
@Composable
private fun LearnedWord(word: WordView, onKeep: (WordView) -> Unit, onRemove: (WordView) -> Unit) {
    val c = RoudioTheme.colors
    val remove = stringResource(R.string.words_drop, word.term)
    Row(
        Modifier.fillMaxWidth().dashedBorder(c.muted, 20.dp, 1.dp).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(word.term, style = roudioText(18.sp, 24.sp, FontWeight.SemiBold), color = c.ink)
            Text(stringResource(R.string.words_learned_body), style = roudioText(14.sp, 20.sp), color = c.muted)
        }
        SmallButton(stringResource(R.string.words_keep), onClick = { onKeep(word) }, filled = true)
        Box(
            Modifier
                .size(44.dp)
                .background(c.raised, CircleShape)
                .clickable(role = Role.Button) { onRemove(word) }
                .semantics { contentDescription = remove },
            contentAlignment = Alignment.Center,
        ) {
            Icon(OrganiseIcons.Close, contentDescription = null, tint = c.ink, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun WordChip(text: String, outlined: Boolean, onClick: () -> Unit) {
    val c = RoudioTheme.colors
    val shape = RoundedCornerShape(22.dp)
    Box(
        Modifier
            .height(44.dp)
            .then(if (outlined) Modifier.border(1.dp, c.hairline, shape) else Modifier.background(c.raised, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = roudioText(16.sp, 20.sp, FontWeight.SemiBold), color = c.ink)
    }
}

private const val COLLAPSED = 11

private val previewWords = listOf("Kumasi", "Efua", "Kojo", "Priya", "Plaud", "abeg", "wahala", "jollof", "Lekki", "Daniel", "Osei", "Accra", "Yaba")
    .mapIndexed { i, term -> WordView(i.toLong(), term, WordView.Source.MANUAL, confirmed = true) } +
    WordView(99, "O’Shea", WordView.Source.LEARNED, confirmed = false)

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun WordListPreview() {
    RoudioTheme(darkTheme = false) { WordListScreen(previewWords, false, false, {}, {}, {}, {}, {}) }
}

@Preview(widthDp = 412, heightDp = 915)
@Composable
private fun WordListDarkPreview() {
    RoudioTheme(darkTheme = true) { WordListScreen(previewWords, false, false, {}, {}, {}, {}, {}) }
}
