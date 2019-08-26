/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.tivi.home.watched

import androidx.lifecycle.viewModelScope
import androidx.paging.PagedList
import app.tivi.TiviMvRxViewModel
import app.tivi.data.entities.SortOption
import app.tivi.data.entities.WatchedShowEntry
import app.tivi.data.resultentities.WatchedShowEntryWithShow
import app.tivi.domain.interactors.UpdateWatchedShows
import app.tivi.domain.invoke
import app.tivi.domain.launchObserve
import app.tivi.domain.observers.ObservePagedWatchedShows
import app.tivi.domain.observers.ObserveTraktAuthState
import app.tivi.tmdb.TmdbManager
import app.tivi.trakt.TraktAuthState
import app.tivi.util.ObservableLoadingCounter
import app.tivi.util.collectFrom
import com.airbnb.mvrx.FragmentViewModelContext
import com.airbnb.mvrx.MvRxViewModelFactory
import com.airbnb.mvrx.ViewModelContext
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WatchedViewModel @AssistedInject constructor(
    @Assisted initialState: WatchedViewState,
    private val updateWatchedShows: UpdateWatchedShows,
    private val observePagedWatchedShows: ObservePagedWatchedShows,
    private val observeTraktAuthState: ObserveTraktAuthState,
    tmdbManager: TmdbManager
) : TiviMvRxViewModel<WatchedViewState>(initialState) {
    private val boundaryCallback = object : PagedList.BoundaryCallback<WatchedShowEntryWithShow>() {
        override fun onZeroItemsLoaded() {
            setState { copy(isEmpty = filter.isNullOrEmpty()) }
        }

        override fun onItemAtEndLoaded(itemAtEnd: WatchedShowEntryWithShow) {
            setState { copy(isEmpty = false) }
        }

        override fun onItemAtFrontLoaded(itemAtFront: WatchedShowEntryWithShow) {
            setState { copy(isEmpty = false) }
        }
    }

    private val loadingState = ObservableLoadingCounter()

    private var selectionOpen = false

    init {
        viewModelScope.launch {
            loadingState.observable
                    .distinctUntilChanged()
                    .debounce(2000)
                    .execute { copy(isLoading = it() ?: false) }
        }

        viewModelScope.launch {
            tmdbManager.imageProviderFlow
                    .execute { copy(tmdbImageUrlProvider = it() ?: tmdbImageUrlProvider) }
        }

        viewModelScope.launchObserve(observePagedWatchedShows) {
            it.execute { copy(watchedShows = it()) }
        }

        viewModelScope.launchObserve(observeTraktAuthState) { flow ->
            flow.distinctUntilChanged().collect {
                if (it == TraktAuthState.LOGGED_IN) {
                    refreshWatched(false)
                }
            }
        }
        observeTraktAuthState()

        // Set the available sorting options
        setState {
            copy(availableSorts = listOf(SortOption.LAST_WATCHED, SortOption.ALPHABETICAL))
        }

        // Subscribe to state changes, so update the observed data source
        subscribe(::updateDataSource)

        refresh(false)
    }

    private fun updateDataSource(state: WatchedViewState) {
        observePagedWatchedShows(
                ObservePagedWatchedShows.Params(
                        sort = state.sort,
                        filter = state.filter,
                        pagingConfig = PAGING_CONFIG,
                        boundaryCallback = boundaryCallback
                )
        )
    }

    fun refresh() = refresh(true)

    private fun refresh(fromUser: Boolean) {
        viewModelScope.launch {
            observeTraktAuthState.observe()
                    .first { it == TraktAuthState.LOGGED_IN }
                    .also { refreshWatched(fromUser) }
        }
    }

    fun setFilter(filter: String) {
        setState { copy(filter = filter, filterActive = filter.isNotEmpty()) }
    }

    fun setSort(sort: SortOption) {
        setState { copy(sort = sort) }
    }

    fun onItemClick(entry: WatchedShowEntry): Boolean {
        if (selectionOpen) {
            setState {
                val currentSelection = when {
                    entry.id in selectedEntryIds -> selectedEntryIds.minus(entry.id)
                    else -> selectedEntryIds.plus(entry.id)
                }
                this@WatchedViewModel.selectionOpen = currentSelection.isNotEmpty()
                copy(
                        selectionOpen = this@WatchedViewModel.selectionOpen,
                        selectedEntryIds = currentSelection
                )
            }
            return true
        }
        return false
    }

    fun onItemLongClick(entry: WatchedShowEntry): Boolean {
        if (!selectionOpen) {
            selectionOpen = true

            setState {
                var currentSelection = selectedEntryIds
                if (entry.id !in currentSelection) {
                    currentSelection = currentSelection.plus(entry.id)
                }
                copy(selectionOpen = selectionOpen, selectedEntryIds = currentSelection)
            }
            return true
        }
        return false
    }

    private fun refreshWatched(fromUser: Boolean) {
        updateWatchedShows(UpdateWatchedShows.Params(fromUser)).also {
            viewModelScope.launch {
                loadingState.collectFrom(it)
            }
        }
    }

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: WatchedViewState): WatchedViewModel
    }

    companion object : MvRxViewModelFactory<WatchedViewModel, WatchedViewState> {
        private val PAGING_CONFIG = PagedList.Config.Builder()
                .setPageSize(60)
                .setPrefetchDistance(20)
                .setEnablePlaceholders(false)
                .build()

        override fun create(viewModelContext: ViewModelContext, state: WatchedViewState): WatchedViewModel? {
            val fragment: WatchedFragment = (viewModelContext as FragmentViewModelContext).fragment()
            return fragment.watchedViewModelFactory.create(state)
        }
    }
}
