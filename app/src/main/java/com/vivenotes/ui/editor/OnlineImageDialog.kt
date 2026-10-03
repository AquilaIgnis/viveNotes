package com.vivenotes.ui.editor

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vivenotes.data.ImageSource
import com.vivenotes.data.OnlineImage
import com.vivenotes.data.OnlineImageSearchResult
import com.vivenotes.data.OnlineImages
import com.vivenotes.data.SearchCursor
import com.vivenotes.ui.icons.MaterialSymbols
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal object OnlineImageTags {
    const val DIALOG = "online-image-dialog"
    const val QUERY = "online-image-query"
    const val CLOSE = "online-image-close"
    const val RESULT = "online-image-result"
    const val MESSAGE = "online-image-message"

    /** Where Openverse's results begin, after Wikimedia Commons'. */
    const val SOURCE_HEADER = "online-image-source-header"

    /** On a tile whose preview could not be fetched at all. It can still be inserted. */
    const val NO_PREVIEW = "online-image-no-preview"
}

/**
 * The Picture menu's "Search online": a query, a grid of results, and a tap to insert. The results
 * are Wikimedia Commons' and then Openverse's — [com.vivenotes.data.OnlineImageSearch] decides
 * where one hands over to the other.
 *
 * The dialog downloads the picked picture itself and closes only once it has the bytes, so a slow
 * or failed download shows on the tile that was tapped rather than vanishing into the page.
 *
 * Further pages load as the grid nears its end. Openverse allows twenty anonymous searches a
 * minute, so a page is fetched only when the last one has landed and nothing has failed.
 *
 * The opt-in is for [LoadingIndicator] and [ContainedLoadingIndicator] — the same one
 * `ExportPdfDialog` takes.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun OnlineImageDialog(
    images: OnlineImages,
    onInsert: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    /** The term the grid is showing results for, which the field may since have moved on from. */
    var searched by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<OnlineImage>() }
    /** Where the next page starts; null when there is none, or before the first search. */
    var next by remember { mutableStateOf<SearchCursor?>(null) }
    var loading by remember { mutableStateOf(false) }
    /** A failure to show. Also what stops further pages loading until the next search. */
    var message by remember { mutableStateOf<String?>(null) }
    /** The id of the picture being fetched for insertion. One at a time; the grid waits on it. */
    var downloading by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val grid = rememberLazyStaggeredGridState()

    fun load(term: String, from: SearchCursor?) {
        loading = true
        searchJob = scope.launch {
            val result = images.search(term, from)
            loading = false
            when (result) {
                is OnlineImageSearchResult.Found -> {
                    // Search pages are not snapshots: a file added between two requests shifts the
                    // next page by one and repeats a hit, and a repeated key would crash the grid.
                    val known = results.mapTo(HashSet()) { it.id }
                    results += result.images.filter { known.add(it.id) }
                    next = result.next
                    message = null
                }
                OnlineImageSearchResult.RateLimited -> {
                    next = null
                    message = "Too many searches from this connection. Try again in a minute."
                }
                OnlineImageSearchResult.Unreachable -> {
                    next = null
                    message = "Couldn’t reach the picture search. Check the connection and try again."
                }
            }
        }
    }

    fun search() {
        val term = query.trim()
        if (term.isEmpty() || downloading != null) return
        keyboard?.hide()
        searchJob?.cancel()
        results.clear()
        searched = term
        next = null
        message = null
        load(term, from = null)
        scope.launch { grid.scrollToItem(0) }
    }

    fun pick(image: OnlineImage) {
        if (downloading != null) return
        downloading = image.id
        message = null
        scope.launch {
            val bytes = images.download(image)
            downloading = null
            if (bytes == null) {
                message = "That picture couldn’t be downloaded. Try another one."
            } else {
                onInsert(bytes)
                onDismiss()
            }
        }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Distinct values only, so a page is requested on each false → true edge: one when the grid
    // first nears its end, and again after each page lands if the grid is still short of the bottom.
    LaunchedEffect(grid) {
        snapshotFlow {
            val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            next != null && !loading && message == null && last >= results.size - PREFETCH_DISTANCE
        }.collect { wanted ->
            val term = searched
            val from = next
            if (wanted && term != null && from != null) load(term, from)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(DIALOG_MARGIN)
                .sizeIn(maxWidth = MAX_WIDTH, maxHeight = MAX_HEIGHT)
                .fillMaxSize()
                .testTag(OnlineImageTags.DIALOG),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 24.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag(OnlineImageTags.CLOSE)) {
                        Icon(MaterialSymbols.Close, contentDescription = "Close")
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Search online", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Openly licensed pictures from Wikimedia Commons and Openverse",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search pictures") },
                    leadingIcon = { Icon(MaterialSymbols.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) {
                        {
                            IconButton(onClick = { query = "" }) {
                                Icon(MaterialSymbols.Close, contentDescription = "Clear search")
                            }
                        }
                    } else {
                        null
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { search() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                        .focusRequester(focus)
                        .testTag(OnlineImageTags.QUERY),
                )

                if (results.isNotEmpty()) {
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .testTag(OnlineImageTags.MESSAGE),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        results.isNotEmpty() -> LazyVerticalStaggeredGrid(
                            columns = StaggeredGridCells.Adaptive(TILE_MIN_WIDTH),
                            state = grid,
                            contentPadding = PaddingValues(16.dp),
                            verticalItemSpacing = TILE_SPACING,
                            horizontalArrangement = Arrangement.spacedBy(TILE_SPACING),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            // Commons comes first; where Openverse takes over is marked, so a
                            // change in what the pictures are like has an explanation on screen.
                            val handOver = results.indexOfFirst { it.source == ImageSource.Openverse }
                            val tiles: (List<OnlineImage>) -> Unit = { slice ->
                                items(slice, key = { it.id }) { image ->
                                    ResultTile(
                                        images = images,
                                        image = image,
                                        downloading = downloading == image.id,
                                        enabled = downloading == null,
                                        onPick = { pick(image) },
                                    )
                                }
                            }
                            if (handOver <= 0) {
                                tiles(results.toList())
                            } else {
                                tiles(results.take(handOver))
                                item(key = SOURCE_HEADER_KEY, span = StaggeredGridItemSpan.FullLine) {
                                    Text(
                                        text = "More from Openverse",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .padding(top = 16.dp, bottom = 4.dp)
                                            .semantics { heading() }
                                            .testTag(OnlineImageTags.SOURCE_HEADER),
                                    )
                                }
                                tiles(results.drop(handOver))
                            }
                            if (loading) {
                                item(span = StaggeredGridItemSpan.FullLine) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        LoadingIndicator()
                                    }
                                }
                            }
                        }
                        loading -> LoadingIndicator()
                        else -> Text(
                            text = message
                                ?: searched?.let { "No pictures found for “$it”." }
                                ?: "Search for photos and illustrations you can put on the page.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (message != null) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .padding(32.dp)
                                .testTag(OnlineImageTags.MESSAGE),
                        )
                    }
                }
            }
        }
    }
}

/**
 * One result, at its own aspect ratio so the grid reads like a page of pictures rather than a page
 * of crops. The ratio is clamped: a panorama would otherwise be a sliver nobody could tap.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultTile(
    images: OnlineImages,
    image: OnlineImage,
    downloading: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val preview by produceState<Preview>(Preview.Loading, image.id) {
        value = images.thumbnail(image)?.let { Preview.Shown(it.asImageBitmap()) } ?: Preview.Missing
    }
    val shown by animateFloatAsState(
        targetValue = if (preview is Preview.Shown) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "thumbnail",
    )
    val ratio = if (image.width > 0 && image.height > 0) {
        (image.width.toFloat() / image.height).coerceIn(MIN_RATIO, MAX_RATIO)
    } else {
        1f
    }
    val description = image.title.ifBlank { "Untitled picture" }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClickLabel = "Insert", role = Role.Button, onClick = onPick)
            .semantics { contentDescription = description }
            .testTag(OnlineImageTags.RESULT),
        contentAlignment = Alignment.Center,
    ) {
        when (val current = preview) {
            is Preview.Shown -> Image(
                bitmap = current.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = shown },
            )
            // Said outright, or a tile with no preview looks like one still loading — forever.
            Preview.Missing -> Icon(
                imageVector = MaterialSymbols.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(NO_PREVIEW_ICON)
                    .testTag(OnlineImageTags.NO_PREVIEW),
            )
            Preview.Loading -> Unit
        }
        if (downloading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)),
            )
            ContainedLoadingIndicator()
        }
    }
}

private sealed interface Preview {
    data object Loading : Preview
    data class Shown(val bitmap: ImageBitmap) : Preview
    data object Missing : Preview
}

/** Load the next page this many tiles before the end, so scrolling rarely meets the spinner. */
private const val PREFETCH_DISTANCE = 6
private const val SOURCE_HEADER_KEY = "source-header"
private const val MIN_RATIO = 0.5f
private const val MAX_RATIO = 2f
private const val SCRIM_ALPHA = 0.32f
private val TILE_MIN_WIDTH = 160.dp
private val TILE_SPACING = 8.dp
private val NO_PREVIEW_ICON = 32.dp
private val DIALOG_MARGIN = 16.dp
private val MAX_WIDTH = 900.dp
private val MAX_HEIGHT = 760.dp
