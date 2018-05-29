/*
 * Copyright 2017 Google, Inc.
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

package app.tivi.trakt.calls

import app.tivi.ShowFetcher
import app.tivi.api.ItemWithIndex
import app.tivi.calls.PaginatedEntryCallImpl
import app.tivi.data.DatabaseTransactionRunner
import app.tivi.data.daos.PopularDao
import app.tivi.data.daos.TiviShowDao
import app.tivi.data.entities.PopularEntry
import app.tivi.data.entities.PopularListItem
import app.tivi.extensions.fetchBodyWithRetry
import app.tivi.util.AppCoroutineDispatchers
import app.tivi.util.AppRxSchedulers
import app.tivi.util.Logger
import com.uwetrottmann.trakt5.entities.Show
import com.uwetrottmann.trakt5.enums.Extended
import com.uwetrottmann.trakt5.services.Shows
import javax.inject.Inject
import javax.inject.Provider

class PopularCall @Inject constructor(
    databaseTransactionRunner: DatabaseTransactionRunner,
    showDao: TiviShowDao,
    popularDao: PopularDao,
    private val showFetcher: ShowFetcher,
    private val showsService: Provider<Shows>,
    schedulers: AppRxSchedulers,
    dispatchers: AppCoroutineDispatchers,
    logger: Logger
) : PaginatedEntryCallImpl<ItemWithIndex<Show>, PopularEntry, PopularListItem, PopularDao>(
        databaseTransactionRunner,
        showDao,
        popularDao,
        showFetcher,
        schedulers,
        dispatchers,
        logger
) {

    override suspend fun networkCall(page: Int): List<ItemWithIndex<Show>> {
        // We add one to the page since Trakt uses a 1-based index whereas we use 0-based
        return showsService.get().popular(page + 1, pageSize, Extended.NOSEASONS)
                .fetchBodyWithRetry()
                .mapIndexed { index, show -> ItemWithIndex(show, index) }
    }

    override fun mapToEntry(networkEntity: ItemWithIndex<Show>, showId: Long, page: Int): PopularEntry {
        return PopularEntry(showId = showId, page = page, pageOrder = networkEntity.index)
    }

    override suspend fun insertShowPlaceholder(response: ItemWithIndex<Show>): Long {
        return showFetcher.insertPlaceholderIfNeeded(response.item)
    }
}
