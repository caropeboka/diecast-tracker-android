package io.github.caropeboka.diecasttracker.ui.components

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.ui.theme.Garage
import io.github.caropeboka.diecasttracker.util.Currency
import io.github.caropeboka.diecasttracker.util.Formats
import io.github.caropeboka.diecasttracker.util.convertFromIdr
import io.github.caropeboka.diecasttracker.util.convertToIdr
import io.github.caropeboka.diecasttracker.util.formatDate
import io.github.caropeboka.diecasttracker.util.groupThousands
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** shadcn's dark input: a faint fill and the input border. */
@Composable
fun fieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Garage.Input.copy(alpha = 0.3f),
    focusedContainerColor = Garage.Input.copy(alpha = 0.3f),
    disabledContainerColor = Garage.Input.copy(alpha = 0.15f),
    errorContainerColor = Garage.Input.copy(alpha = 0.3f),
    unfocusedBorderColor = Garage.Input,
)

private fun errorText(error: String?): (@Composable () -> Unit)? = error?.let { { Text(it) } }

@Composable
fun TextInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences,
    singleLine: Boolean = true,
    minLines: Int = 1,
    highlighted: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { FieldLabel(label, highlighted) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = errorText(error),
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            capitalization = capitalization,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
        ),
        colors = if (highlighted) highlightColors() else fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Fields the AI filled in get a brand-tinted border. */
@Composable
private fun highlightColors() = OutlinedTextFieldDefaults.colors(
    unfocusedContainerColor = Garage.Brand.copy(alpha = 0.08f),
    focusedContainerColor = Garage.Brand.copy(alpha = 0.08f),
    unfocusedBorderColor = Garage.Brand.copy(alpha = 0.6f),
)

/** The label, with ✦ when the AI filled the field in (never colour-only). */
@Composable
private fun FieldLabel(text: String, highlighted: Boolean) {
    if (highlighted) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text)
            Icon(painterResource(R.drawable.ic_sparkles), contentDescription = stringResource(R.string.filled_by_ai), tint = Garage.Brand, modifier = Modifier.size(14.dp))
        }
    } else {
        Text(text)
    }
}

/**
 * A price. [value] always holds whole Rupiah as digits, whatever it is typed in: in Rupiah it's shown grouped
 * ("35.000"); in dollars or yuan it's converted at the day's rate and may have cents. Only what is typed is converted
 * back, so a price left alone keeps its exact Rupiah.
 */
@Composable
fun MoneyInput(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    highlighted: Boolean = false,
) {
    val currency = Formats.shownCurrency
    if (currency == Currency.IDR) {
        OutlinedTextField(
            value = value,
            onValueChange = { text ->
                val digits = text.filter(Char::isDigit).trimStart('0').take(12)
                onValueChange(if (digits.isEmpty() && text.any(Char::isDigit)) "0" else digits)
            },
            label = { FieldLabel(label, highlighted) },
            prefix = { Text("Rp ") },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = errorText(error),
            singleLine = true,
            visualTransformation = remember(Formats.locale) { ThousandsTransformation(groupThousands(1000)[1]) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
            colors = if (highlighted) highlightColors() else fieldColors(),
            modifier = modifier.fillMaxWidth(),
        )
        return
    }

    // What's shown is kept while typing, so converting back and forth doesn't rewrite it under the finger; a value
    // changed from outside (the AI, "Pakai") is shown afresh.
    var text by remember(currency) { mutableStateOf(foreignAmount(value)) }
    var sent by remember(currency) { mutableStateOf(value) }
    if (value != sent) {
        text = foreignAmount(value)
        sent = value
    }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val clean = decimalAmount(typed)
            text = clean
            sent = clean.toDoubleOrNull()?.let { convertToIdr(it).toString() }.orEmpty()
            onValueChange(sent)
        },
        label = { FieldLabel(label, highlighted) },
        prefix = { Text(currency.symbol) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = errorText(error),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        colors = if (highlighted) highlightColors() else fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Rupiah digits as the chosen currency's amount for typing: "35000" → "1.93". */
private fun foreignAmount(idr: String): String {
    val amount = convertFromIdr(idr.toLongOrNull() ?: return "")
    return String.format(Locale.ROOT, "%.2f", amount).removeSuffix(".00")
}

/** Digits with at most one decimal point (a comma counts as one) and two decimals. */
private fun decimalAmount(typed: String): String {
    val normalized = typed.replace(',', '.').filter { it.isDigit() || it == '.' }
    val whole = normalized.substringBefore('.').trimStart('0').take(9).ifEmpty { if ('.' in normalized || normalized.isNotEmpty()) "0" else "" }
    if ('.' !in normalized) return whole
    return whole + "." + normalized.substringAfter('.').filter(Char::isDigit).take(2)
}

/** Shows "1234567" as "1.234.567" (or with commas) and keeps the cursor on the same digit. */
private class ThousandsTransformation(private val mark: Char) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val digits = text.text
        val length = digits.length
        val formatted = buildString {
            digits.forEachIndexed { i, c ->
                if (i > 0 && (length - i) % 3 == 0) append(mark)
                append(c)
            }
        }

        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                offset + (1 until offset).count { (length - it) % 3 == 0 }

            override fun transformedToOriginal(offset: Int): Int =
                formatted.take(offset).count(Char::isDigit)
        }
        return TransformedText(AnnotatedString(formatted), mapping)
    }
}

/** A read-only field that opens a menu of fixed choices. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceInput(
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { FieldLabel(label, highlighted) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            colors = if (highlighted) highlightColors() else fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    trailingIcon = if (option == selected) {
                        { Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** Free text with suggestions from values used before (the web form's datalists). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestInput(
    value: String,
    onValueChange: (String) -> Unit,
    suggestions: List<String>,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Words,
    highlighted: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val matches = remember(value, suggestions) {
        suggestions
            .filter { it.contains(value.trim(), ignoreCase = true) && !it.equals(value.trim(), ignoreCase = true) }
            .take(8)
    }
    val expanded = focused && !dismissed && matches.isNotEmpty()

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { dismissed = !it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                dismissed = false
                onValueChange(it)
            },
            label = { FieldLabel(label, highlighted) },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = errorText(error),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = capitalization, imeAction = ImeAction.Next),
            colors = if (highlighted) highlightColors() else fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { dismissed = true }) {
            matches.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onValueChange(option)
                        dismissed = true
                    },
                )
            }
        }
    }
}

/** A date shown as "18 Okt 2026"; tapping opens the date picker. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateInput(
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    clearable: Boolean = true,
    latest: LocalDate? = null,
    error: String? = null,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    LaunchedEffect(interaction) {
        interaction.interactions.collect {
            if (it is PressInteraction.Release) picking = true
        }
    }

    OutlinedTextField(
        value = date?.let(::formatDate).orEmpty(),
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        label = { Text(label) },
        placeholder = { Text(stringResource(R.string.pick_date)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_calendar), contentDescription = null, Modifier.size(20.dp)) },
        trailingIcon = if (clearable && date != null) {
            {
                IconButton(onClick = { onDateChange(null) }) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.field_clear, label))
                }
            }
        } else {
            null
        },
        isError = error != null,
        supportingText = errorText(error),
        interactionSource = interaction,
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) =
                    latest == null || !utcTimeMillis.toUtcDate().isAfter(latest)

                override fun isSelectableYear(year: Int) = latest == null || year <= latest.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(it.toUtcDate()) }
                    picking = false
                }) { Text(stringResource(R.string.choose)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun Long.toUtcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

/** Chips input: type a tag and press enter or a comma. Tags used before are offered below. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagInput(
    tags: List<String>,
    onTagsChange: (List<String>) -> Unit,
    suggestions: List<String>,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    var draft by rememberSaveable { mutableStateOf("") }

    fun has(tag: String) = tags.any { it.equals(tag, ignoreCase = true) }

    fun add(raw: String) {
        val new = raw.split(',').map(String::trim).filter { it.isNotEmpty() && !has(it) }.distinctBy(String::lowercase)
        if (new.isNotEmpty()) onTagsChange(tags + new)
        draft = ""
    }

    val unused = suggestions.filter { !has(it) && it.contains(draft.trim(), ignoreCase = true) }.take(10)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onTagsChange(tags - tag) },
                        label = { Text(tag) },
                        trailingIcon = {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.tag_remove, tag), Modifier.size(16.dp))
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { text -> if (',' in text) add(text) else draft = text },
            label = { Text(stringResource(R.string.field_tags)) },
            placeholder = { Text(stringResource(R.string.tag_placeholder)) },
            isError = error != null,
            supportingText = errorText(error),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(draft) }),
            trailingIcon = if (draft.isNotBlank()) {
                {
                    IconButton(onClick = { add(draft) }) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.tag_add))
                    }
                }
            } else {
                null
            },
            colors = fieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused && draft.isNotBlank()) add(draft) },
        )
        if (unused.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                unused.forEach { tag ->
                    SuggestionChip(onClick = { add(tag) }, label = { Text("+ $tag", style = MaterialTheme.typography.labelMedium) })
                }
            }
        }
    }
}
