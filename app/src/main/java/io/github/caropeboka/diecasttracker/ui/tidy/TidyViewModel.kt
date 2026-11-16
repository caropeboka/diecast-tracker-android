package io.github.caropeboka.diecasttracker.ui.tidy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.caropeboka.diecasttracker.DiecastTrackerApp
import io.github.caropeboka.diecasttracker.Messages
import io.github.caropeboka.diecasttracker.R
import io.github.caropeboka.diecasttracker.data.DiecastRepository
import io.github.caropeboka.diecasttracker.data.UserPrefs
import io.github.caropeboka.diecasttracker.data.db.DiecastListItem
import io.github.caropeboka.diecasttracker.data.locale.Texts
import io.github.caropeboka.diecasttracker.data.tidy.DuplicateGroup
import io.github.caropeboka.diecasttracker.data.tidy.SpellingGroup
import io.github.caropeboka.diecasttracker.data.tidy.Tidy
import io.github.caropeboka.diecasttracker.data.tidy.TidyField
import io.github.caropeboka.diecasttracker.data.tidy.TidyReport
import io.github.caropeboka.diecasttracker.data.tidy.toTidyRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [items]: every diecast's card, for the duplicates; [skipped]: something was left as it was. */
data class TidyState(val report: TidyReport, val items: Map<Long, DiecastListItem>, val skipped: Boolean)

/** A spelling written one way, with what each diecast said before, for "Urungkan". */
data class Renamed(val field: TidyField, val old: Map<Long, String>, val message: String)

/** The tidy-up of the whole collection, kept up to date as diecasts change. */
fun observeTidyReport(repository: DiecastRepository, prefs: UserPrefs): Flow<TidyState> =
    combine(repository.observeDashboard(), prefs.tidyIgnored) { rows, ignored ->
        TidyState(Tidy.report(rows.map { it.toTidyRow() }, ignored), rows.associate { it.item.id to it.item }, ignored.isNotEmpty())
    }.flowOn(Dispatchers.Default)

class TidyViewModel(
    private val repository: DiecastRepository,
    private val prefs: UserPrefs,
    private val texts: Texts,
    private val messages: Messages,
) : ViewModel() {
    val state: StateFlow<TidyState?> = observeTidyReport(repository, prefs)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A change is being written. */
    var busy by mutableStateOf(false)
        private set

    private val events = Channel<Renamed>(Channel.BUFFERED)

    /** Each change made, for a snackbar with "Urungkan". */
    val renamed = events.receiveAsFlow()

    /** Every diecast in [group] written another way than [to] takes [to]. */
    fun apply(group: SpellingGroup, to: String) = work {
        val ids = group.spellings.filter { it.text != to }.flatMap { it.ids }
        val old = repository.rename(group.field, ids, group.spellings.map { it.text }, to)
        if (old.isNotEmpty()) events.send(Renamed(group.field, old, texts.plural(R.plurals.tidy_applied, old.size, old.size, to)))
    }

    fun undo(renamed: Renamed) = work {
        repository.restore(renamed.field, renamed.old)
        messages.show(texts(R.string.tidy_undone))
    }

    fun leave(group: SpellingGroup) {
        viewModelScope.launch { prefs.ignoreTidy(Tidy.ignoreKeys(group)) }
    }

    fun notDuplicate(group: DuplicateGroup) {
        viewModelScope.launch { prefs.ignoreTidy(Tidy.ignoreKeys(group)) }
    }

    fun showSkipped() {
        viewModelScope.launch { prefs.clearTidyIgnored() }
    }

    private fun work(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.show(texts(R.string.tidy_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                busy = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as DiecastTrackerApp
                with(app.container) { TidyViewModel(repository, prefs, texts, messages) }
            }
        }
    }
}
