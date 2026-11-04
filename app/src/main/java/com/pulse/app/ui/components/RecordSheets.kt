package com.pulse.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.app.PulseGraph
import com.pulse.app.data.MerchantRule
import com.pulse.app.data.MoneyRecord
import com.pulse.app.domain.Categoriser
import com.pulse.app.domain.Category
import com.pulse.app.domain.RecordType
import com.pulse.app.domain.formatRupees
import kotlinx.coroutines.launch

/** Shared actions on records, used by Home and Records. */
open class RecordActionsViewModel(protected val graph: PulseGraph) : ViewModel() {
    fun addManual(amountPaise: Long, merchant: String, type: RecordType) {
        viewModelScope.launch {
            graph.db.moneyRecordDao().insert(
                MoneyRecord(
                    packageName = MoneyRecord.MANUAL,
                    amountPaise = amountPaise,
                    merchant = merchant.trim().ifEmpty { null },
                    type = type,
                    occurredAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    /** Saves a rule, so every record from this merchant gets this category from now on. */
    fun setCategory(merchant: String, category: Category) {
        viewModelScope.launch {
            graph.db.merchantRuleDao().upsert(MerchantRule(merchant.lowercase().trim(), category.name))
        }
    }

    fun delete(record: MoneyRecord) {
        viewModelScope.launch { graph.db.moneyRecordDao().delete(record) }
    }
}

/** "1,250.50" -> 125050 paise. Null if it is not a positive amount. */
fun parseRupees(text: String): Long? {
    val clean = text.replace(",", "").replace("₹", "").trim()
    if (!Regex("""\d{1,12}(\.\d{1,2})?""").matches(clean)) return null
    val parts = clean.split(".")
    val paise = parts[0].toLong() * 100 + (parts.getOrNull(1)?.padEnd(2, '0')?.toLong() ?: 0L)
    return paise.takeIf { it > 0 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordSheet(onDismiss: () -> Unit, onSave: (Long, String, RecordType) -> Unit) {
    var amount by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(RecordType.EXPENSE) }
    val paise = parseRupees(amount)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add a record", style = MaterialTheme.typography.titleLarge)
            FilterPills(
                options = listOf(RecordType.EXPENSE to "Spent", RecordType.INCOME to "Received"),
                selected = type,
                onSelect = { type = it },
            )
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount in ₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(if (type == RecordType.EXPENSE) "Paid to" else "Received from") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { paise?.let { onSave(it, name, type); onDismiss() } },
                enabled = paise != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDetailSheet(
    record: MoneyRecord,
    rules: Map<String, Category>,
    onDismiss: () -> Unit,
    onCategory: (String, Category) -> Unit,
    onDelete: (MoneyRecord) -> Unit,
) {
    val name = record.merchant
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(name ?: "Unknown", style = MaterialTheme.typography.titleLarge)
            Text(formatRupees(record.amountPaise), style = MaterialTheme.typography.headlineMedium)
            if (name != null && record.type == RecordType.EXPENSE) {
                Text(
                    "Category. Your choice applies to every payment to $name.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilterPills(
                    options = Category.entries.map { it to it.label },
                    selected = Categoriser.of(name, rules),
                    onSelect = { onCategory(name, it) },
                )
            }
            TextButton(onClick = { onDelete(record); onDismiss() }) { Text("Delete this record") }
        }
    }
}
