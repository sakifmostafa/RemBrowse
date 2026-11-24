package com.rembrowser.rembrowse.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.print.PrintJob
import android.print.PrintManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.rembrowser.rembrowse.engine.GeckoPagePrinter
import com.rembrowser.rembrowse.engine.PdfFilePrintAdapter
import com.rembrowser.rembrowse.model.PrintPreview
import com.rembrowser.rembrowse.ui.theme.RemMotion
import com.rembrowser.rembrowse.util.UrlResolver
import java.io.File
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PrintScreen"

/** From this width the previews and the panel sit side by side; below it they are stacked. */
private val SideBySideMinWidth = 720.dp

/** From this width the side-by-side layout's settings panel is 380dp wide instead of 340dp. */
private val WidePanelMinWidth = 900.dp

/** From this width the stacked layout puts its Save as PDF and Print tiles side by side. */
private val TilesSideBySideMinWidth = 520.dp

/** In a compact window the panel may take this much of the height; the previews keep the rest. */
private const val COMPACT_PANEL_MAX_FRACTION = 0.6f

/** Paper is nearly square-cornered. */
private val PaperShape = RoundedCornerShape(2.dp)

/**
 * The paper is always white, whatever the theme, so what is drawn on it uses the light theme's
 * secondary text (7:1 on white) and outline (4.5:1) rather than the current scheme's.
 */
private val PaperInk = Color(0xFF555962)
private val PaperMuted = Color(0xFF747985)

/** Pages are rendered at the width they are shown at, in steps, so a resize doesn't re-render each pixel. */
private const val RENDER_WIDTH_STEP_PX = 160
private const val MAX_RENDER_WIDTH_PX = 1600

/** About 16 MB per page at most, whatever the page's proportions. */
private const val MAX_RENDER_PIXELS = 4_000_000L

/** Letter's proportions, until the PDF says otherwise. */
private const val DEFAULT_PAGE_ASPECT = 612f / 792f

/** What the print screen's actions do; each runs at most once between them. */
private class PrintActions(
    /** True once the PDF went to Downloads and the screen is closing. */
    val save: () -> Boolean,
    val print: () -> Unit,
    val cancel: () -> Unit
)

/** What the panel shows and edits. */
private class PrintForm(
    val preview: PrintPreview,
    val details: PrintDetails,
    val fileName: String,
    val onFileNameChange: (String) -> Unit,
    val saveFailed: Boolean,
    val saveFocus: FocusRequester,
    /** Enter was taken in the name field and closed the screen: its release must not reach the page. */
    val onEnterTaken: () -> Unit,
    val actions: PrintActions
)

/**
 * RemBrowse's print screen, shown once a page has been laid out for printing (the Print command,
 * Ctrl+P or the page's own print button) and before anything leaves the browser: the pages as
 * they will come out, what the document is, the name to save it under, and where it goes. Save
 * as PDF puts it in Downloads; Print… hands it to Android's print dialog for a printer.
 *
 * On a wide window the pages and the panel sit side by side; on a compact one they are stacked,
 * with the actions under the pages. Esc, Back and a click outside cancel; Enter in the name field
 * saves. Focus starts on Save as PDF rather than the name field, so no keyboard pops up on touch.
 */
@Composable
internal fun PrintScreen(
    preview: PrintPreview,
    /** Saves under the name given; false when it couldn't, and the screen stays. */
    onSaveAsPdf: (fileName: String) -> Boolean,
    /** Sends the PDF to a printer, as a job with this name. */
    onPrint: (jobName: String) -> Unit,
    onCancel: () -> Unit,
    onEnterTaken: () -> Unit = {},
    /** Opens the PDF for the previews; tests hand in pages of their own. */
    openDocument: (File) -> PdfPreviewDocument = ::PdfPreviewDocument
) {
    val reduceMotion = rememberReduceMotion()

    val document = remember(preview.pdfPath) { openDocument(File(preview.pdfPath)) }
    var load by remember(document) { mutableStateOf<PreviewLoad>(PreviewLoad.Opening) }
    DisposableEffect(document) {
        val request = document.open { info -> load = if (info != null) PreviewLoad.Ready(info) else PreviewLoad.Failed }
        onDispose {
            request.cancel()
            document.close()
        }
    }

    var fileName by remember(preview.serial) { mutableStateOf(editableFileName(preview.documentName)) }
    var saveFailed by remember(preview.serial) { mutableStateOf(false) }
    var finished by remember(preview.serial) { mutableStateOf(false) }
    val saveFocus = remember { FocusRequester() }

    val actions = PrintActions(
        save = save@{
            if (finished) return@save false
            val saved = onSaveAsPdf(fileNameToSave(fileName, preview.documentName))
            if (saved) finished = true else saveFailed = true
            saved
        },
        print = {
            if (!finished) {
                finished = true
                onPrint(printJobName(fileName, preview.documentName))
            }
        },
        cancel = {
            finished = true
            onCancel()
        }
    )
    val form = PrintForm(
        preview = preview,
        // The configuration's locale, which recomposes when the device language changes
        details = printDetails(preview, load, LocalConfiguration.current.locales[0]),
        fileName = fileName,
        onFileNameChange = {
            fileName = it
            saveFailed = false
        },
        saveFailed = saveFailed,
        saveFocus = saveFocus,
        onEnterTaken = onEnterTaken,
        actions = actions
    )
    val cancel by rememberUpdatedState(actions.cancel)

    Dialog(
        onDismissRequest = { cancel() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        TouchpadAsMouseInDialog()
        ResizeForKeyboard()
        LaunchedEffect(Unit) {
            // Before the window's focus arrives, which would otherwise go to the first thing that
            // can take it: the name field, and the keyboard with it. Again once laid out.
            runCatching { saveFocus.requestFocus() }
            withFrameNanos { }
            runCatching { saveFocus.requestFocus() }
        }
        val colors = MaterialTheme.colorScheme
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                // A tap around the screen (not on it) cancels, as a click outside a dialog does
                .pointerInput(Unit) { detectTapGestures { cancel() } }
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        cancel()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val sideBySide = maxWidth >= SideBySideMinWidth
            val margin = if (sideBySide) 24.dp else 12.dp
            val panelMaxHeight = (maxHeight - margin * 2) * COMPACT_PANEL_MAX_FRACTION
            Surface(
                modifier = Modifier
                    .padding(margin)
                    .widthIn(max = 1200.dp)
                    .heightIn(max = 900.dp)
                    .fillMaxSize()
                    // Taps on the screen itself never count as taps around it
                    .pointerInput(Unit) { detectTapGestures { } }
                    .testTag("print_screen"),
                shape = MaterialTheme.shapes.large,
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outlineVariant),
                shadowElevation = 12.dp
            ) {
                if (sideBySide) {
                    SideBySideLayout(
                        form = form,
                        document = document,
                        load = load,
                        reduceMotion = reduceMotion,
                        panelWidth = if (maxWidth >= WidePanelMinWidth) 380.dp else 340.dp
                    )
                } else {
                    StackedLayout(
                        form = form,
                        document = document,
                        load = load,
                        reduceMotion = reduceMotion,
                        panelMaxHeight = panelMaxHeight,
                        tilesSideBySide = maxWidth >= TilesSideBySideMinWidth
                    )
                }
            }
        }
    }
}

/** Wide windows: the pages on the left, the panel on the right with its actions at the bottom. */
@Composable
private fun SideBySideLayout(
    form: PrintForm,
    document: PdfPreviewDocument,
    load: PreviewLoad,
    reduceMotion: Boolean,
    panelWidth: Dp
) {
    Row(modifier = Modifier.fillMaxSize()) {
        PreviewPane(
            document = document,
            load = load,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )
        VerticalHairline()
        Column(
            modifier = Modifier
                .width(panelWidth)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                PanelHeader(onClose = form.actions.cancel)
                Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Spacer(modifier = Modifier.height(8.dp))
                    DocumentCard(form)
                    Spacer(modifier = Modifier.height(20.dp))
                    FileNameField(form)
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)) {
                SaveError(form)
                SaveTile(form, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                PrintTile(form, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                CancelButton(form, Modifier.align(Alignment.End))
            }
        }
    }
}

/** Compact windows and phones: a short header, the pages, then the name and the actions. */
@Composable
private fun StackedLayout(
    form: PrintForm,
    document: PdfPreviewDocument,
    load: PreviewLoad,
    reduceMotion: Boolean,
    panelMaxHeight: Dp,
    tilesSideBySide: Boolean
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        PanelHeader(onClose = form.actions.cancel, summary = form.details.summary)
        HorizontalDivider(color = colors.outlineVariant)
        PreviewPane(
            document = document,
            load = load,
            reduceMotion = reduceMotion,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        HorizontalDivider(color = colors.outlineVariant)
        Column(
            modifier = Modifier
                .heightIn(max = panelMaxHeight.coerceAtLeast(160.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            FileNameField(form)
            Spacer(modifier = Modifier.height(12.dp))
            SaveError(form)
            if (tilesSideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SaveTile(form, Modifier.weight(1f))
                    PrintTile(form, Modifier.weight(1f))
                }
            } else {
                SaveTile(form, Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                PrintTile(form, Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(4.dp))
            CancelButton(form, Modifier.align(Alignment.End))
        }
    }
}

/** "Print" and a close button; on compact windows the document's details go under the title. */
@Composable
private fun PanelHeader(onClose: () -> Unit, summary: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Print",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        RemIconButton(
            icon = Icons.Outlined.Close,
            label = "Close",
            onClick = onClose,
            modifier = Modifier.keyboardFocusRing(MaterialTheme.shapes.medium)
        )
    }
}

/** The document: its name and site, its page count and paper. */
@Composable
private fun DocumentCard(form: PrintForm) {
    val colors = MaterialTheme.colorScheme
    val host = remember(form.preview.pageUrl) {
        form.preview.pageUrl?.let { UrlResolver.extractDisplayHost(it) }?.takeIf { it.isNotBlank() }
    }
    ListCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = form.preview.documentName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (host != null) {
                Text(
                    text = host,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = colors.outlineVariant)
            DetailRow(label = "Pages", value = form.details.pages)
            Spacer(modifier = Modifier.height(8.dp))
            DetailRow(label = "Paper", value = form.details.paper)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun FileNameField(form: PrintForm) {
    OutlinedTextField(
        value = form.fileName,
        onValueChange = form.onFileNameChange,
        label = { Text("File name") },
        suffix = { Text(".pdf") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { form.actions.save() }),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("print_file_name")
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    // Only when the screen closes does the release land in the browser's window
                    if (form.actions.save()) form.onEnterTaken()
                    true
                } else {
                    false
                }
            }
    )
}

/** Saving to Downloads didn't work: said next to the button, with what to do instead. */
@Composable
private fun SaveError(form: PrintForm) {
    if (!form.saveFailed) return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(colors.errorContainer, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .testTag("print_save_error"),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = colors.onErrorContainer,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "Couldn't save the PDF to Downloads. Try again, or use Print… to save it from Android's print options.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onErrorContainer
        )
    }
}

/**
 * The primary action, where focus starts. A button only takes focus from a keyboard, so this one
 * has a focus of its own that it takes on touch too (keeping it off the name field), and answers
 * Enter and Space itself; the button's own focus is turned off, so Tab stops here once.
 */
@Composable
private fun SaveTile(form: PrintForm, modifier: Modifier = Modifier) {
    val save = { form.actions.save() }
    ActionTile(
        icon = Icons.Outlined.FileDownload,
        title = "Save as PDF",
        detail = "To Downloads",
        primary = true,
        onClick = { save() },
        modifier = modifier,
        focusModifier = Modifier
            .focusRequester(form.saveFocus)
            .onKeyEvent { event ->
                val activates = event.key == Key.Enter || event.key == Key.NumPadEnter ||
                    event.key == Key.Spacebar || event.key == Key.DirectionCenter
                // Acts on the release, as a button does, so no half key press is left for the page
                if (activates && event.type == KeyEventType.KeyUp) save()
                activates
            }
            .focusable()
            .focusProperties { canFocus = false }
    )
}

@Composable
private fun PrintTile(form: PrintForm, modifier: Modifier = Modifier) {
    ActionTile(
        icon = Icons.Outlined.Print,
        title = "Print…",
        detail = "Send to a printer",
        primary = false,
        onClick = form.actions.print,
        modifier = modifier
    )
}

@Composable
private fun CancelButton(form: PrintForm, modifier: Modifier = Modifier) {
    TextButton(
        onClick = form.actions.cancel,
        modifier = modifier
            .heightIn(min = 48.dp)
            .keyboardFocusRing(ButtonDefaults.textShape)
    ) {
        Text("Cancel")
    }
}

/** A large button that says what it does and where the page goes. */
@Composable
private fun ActionTile(
    icon: ImageVector,
    title: String,
    detail: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Goes between the focus ring and the button, so the ring follows the focus it adds. */
    focusModifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val tileModifier = modifier
        .heightIn(min = 56.dp)
        .keyboardFocusRing(shape)
        .then(focusModifier)
    val padding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    val content: @Composable RowScope.() -> Unit = {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (primary) LocalContentColor.current else colors.onSurfaceVariant
            )
        }
    }
    if (primary) {
        Button(onClick = onClick, modifier = tileModifier, shape = shape, contentPadding = padding, content = content)
    } else {
        RemOutlinedButton(
            onClick = onClick,
            modifier = tileModifier,
            shape = shape,
            contentPadding = padding,
            content = content
        )
    }
}

/** The pages on a tinted backdrop, or why they can't be shown. */
@Composable
private fun PreviewPane(
    document: PdfPreviewDocument,
    load: PreviewLoad,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("print_preview"),
        contentAlignment = Alignment.Center
    ) {
        when (load) {
            PreviewLoad.Opening -> DelayedLoading(
                spinnerColor = MaterialTheme.colorScheme.primary,
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                reduceMotion = reduceMotion
            )
            PreviewLoad.Failed -> PreviewUnavailable()
            is PreviewLoad.Ready -> PageList(
                document = document,
                info = load.info,
                pageWidth = previewPageWidth(maxWidth, maxHeight, load.info.firstPageAspect),
                reduceMotion = reduceMotion
            )
        }
    }
}

@Composable
private fun PreviewUnavailable() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .padding(24.dp)
            .widthIn(max = 360.dp)
            .testTag("print_preview_unavailable"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Couldn't show a preview",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "You can still save this page as a PDF or print it.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * One page under another, rendered only as they come into view. With focus (Tab), the arrow
 * keys, Page Up/Down, Home and End scroll it.
 */
@Composable
private fun PageList(document: PdfPreviewDocument, info: PdfPreviewInfo, pageWidth: Dp, reduceMotion: Boolean) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .keyboardFocusRing(RectangleShape, inside = true)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val line = with(density) { 64.dp.toPx() }
                val screen = listState.layoutInfo.viewportSize.height * 0.9f
                val delta = when (event.key) {
                    Key.DirectionDown -> line
                    Key.DirectionUp -> -line
                    Key.PageDown -> screen
                    Key.PageUp -> -screen
                    Key.MoveHome -> {
                        scope.launch { listState.scrollToItem(0) }
                        return@onKeyEvent true
                    }
                    Key.MoveEnd -> {
                        scope.launch { listState.scrollToItem(info.pageCount - 1) }
                        return@onKeyEvent true
                    }
                    else -> return@onKeyEvent false
                }
                scope.launch { if (reduceMotion) listState.scrollBy(delta) else listState.animateScrollBy(delta) }
                true
            }
            .semantics { contentDescription = "Preview, ${pagesLabel(info.pageCount)}" }
            .focusable()
            .testTag("print_pages"),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(count = info.pageCount, key = { it }) { index ->
            PreviewPage(
                document = document,
                index = index,
                pageCount = info.pageCount,
                pageWidth = pageWidth,
                defaultAspect = info.firstPageAspect,
                reduceMotion = reduceMotion
            )
        }
    }
}

/** A sheet of white paper with the page on it, and "1 of 2" under it. */
@Composable
private fun PreviewPage(
    document: PdfPreviewDocument,
    index: Int,
    pageCount: Int,
    pageWidth: Dp,
    defaultAspect: Float,
    reduceMotion: Boolean
) {
    val colors = MaterialTheme.colorScheme
    val renderWidth = renderWidthFor(with(LocalDensity.current) { pageWidth.roundToPx() })
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(document, index, renderWidth) {
        val request = document.render(index, renderWidth) { rendered ->
            if (rendered != null) {
                // One rendered at the old width is left to the garbage collector: it may still be on screen
                bitmap = rendered
                failed = false
            } else if (bitmap == null) {
                failed = true
            }
        }
        onDispose { request.cancel() }
    }
    // Once the page has left the screen nothing draws it any more, so its pixels can go now
    DisposableEffect(Unit) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }
    val shown = bitmap
    val imageAlpha by animateFloatAsState(
        targetValue = if (shown != null) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else RemMotion.defaultEffects(),
        label = "page fade"
    )
    val aspect = shown?.let { it.width.toFloat() / it.height } ?: defaultAspect
    val number = index + 1
    val description = if (failed) "Page $number of $pageCount, couldn't be shown" else "Page $number of $pageCount"
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(pageWidth)
                .aspectRatio(aspect)
                .shadow(elevation = 4.dp, shape = PaperShape)
                .background(Color.White, PaperShape)
                .border(1.dp, colors.outlineVariant, PaperShape)
                .semantics {
                    contentDescription = description
                    role = Role.Image
                }
                .testTag("print_page_$number"),
            contentAlignment = Alignment.Center
        ) {
            when {
                shown != null -> {
                    val image = remember(shown) { shown.asImageBitmap() }
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = imageAlpha }
                    )
                }
                failed -> Text(
                    text = "Couldn't show this page",
                    style = MaterialTheme.typography.bodySmall,
                    color = PaperInk,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
                else -> DelayedLoading(spinnerColor = PaperMuted, textColor = PaperInk, reduceMotion = reduceMotion)
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "$number of $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            // The page itself says it, as "Page 1 of 2"
            modifier = Modifier.clearAndSetSemantics { }
        )
    }
}

/**
 * A small spinner, only once a wait has gone on long enough to notice, so quick loads don't
 * flash; with Android's animations off, still words instead.
 */
@Composable
private fun DelayedLoading(spinnerColor: Color, textColor: Color, reduceMotion: Boolean, delayMillis: Long = 400) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        visible = true
    }
    when {
        !visible -> Unit
        reduceMotion -> Text(text = "Loading…", style = MaterialTheme.typography.labelMedium, color = textColor)
        else -> CircularProgressIndicator(color = spinnerColor, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
    }
}

/** The keyboard shrinks the screen rather than covering the name field and the actions. */
@Composable
private fun ResizeForKeyboard() {
    val view = LocalView.current
    DisposableEffect(view) {
        (view.parent as? DialogWindowProvider)?.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        onDispose { }
    }
}

/**
 * Hands the print screen's PDF to Android's print dialog, for a printer. [preview] is the one
 * [com.rembrowser.rembrowse.viewmodel.BrowserViewModel.takePrintPreviewForPrinter] gave up, so its
 * file is ours: the dialog deletes it when it is done, and it is deleted here if the dialog can't open.
 */
internal fun sendPdfToPrinter(context: Context, preview: PrintPreview?, jobName: String) {
    val file = preview?.let { File(it.pdfPath) }
    val job: PrintJob? = file?.let {
        try {
            val activity = GeckoPagePrinter.activityOf(context)
            val printManager = activity?.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            printManager?.print(jobName, PdfFilePrintAdapter(it, jobName), null)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not open the print dialog", e)
            null
        }
    }
    if (job == null) {
        file?.delete()
        Toast.makeText(context.applicationContext, "Couldn't open printing", Toast.LENGTH_SHORT).show()
    }
}

/** The name field starts with the document's name, without the ".pdf" it shows after it. */
internal fun editableFileName(documentName: String): String =
    documentName.trim().removeSuffix(".pdf").removeSuffix(".PDF").trim()

/** The name "Save as PDF" is given: what was typed, or the document's own name if nothing was. */
internal fun fileNameToSave(typed: String, documentName: String): String =
    typed.trim().ifEmpty { documentName }

/** The printer's job name: the file name without ".pdf". */
internal fun printJobName(typed: String, documentName: String): String {
    val name = fileNameToSave(typed, documentName).let {
        if (it.endsWith(".pdf", ignoreCase = true)) it.dropLast(4).trim() else it
    }
    return name.ifEmpty { "Page" }
}

internal fun pagesLabel(count: Int): String = if (count == 1) "1 page" else "$count pages"

/**
 * How wide each page is shown: as wide as the backdrop allows up to a readable cap, and small
 * enough that a whole page fits in its height where that leaves it readable.
 */
internal fun previewPageWidth(areaWidth: Dp, areaHeight: Dp, aspect: Float): Dp {
    val room = areaWidth - 48.dp
    // Padding above and below, and the "1 of 2" label
    val fitsHeight = (areaHeight - 80.dp) * aspect
    return minOf(room, 640.dp, maxOf(fitsHeight, 280.dp)).coerceAtLeast(96.dp)
}

/** The bitmap width a page shown [displayWidthPx] wide is rendered at. */
internal fun renderWidthFor(displayWidthPx: Int): Int {
    val stepped = (displayWidthPx + RENDER_WIDTH_STEP_PX - 1) / RENDER_WIDTH_STEP_PX * RENDER_WIDTH_STEP_PX
    return stepped.coerceIn(RENDER_WIDTH_STEP_PX, MAX_RENDER_WIDTH_PX)
}

/** A paper size: its name when it has a common one (A4, Letter, Legal), and its dimensions. */
internal data class PaperSize(val name: String?, val dimensions: String) {
    val shortLabel: String get() = name ?: dimensions
    val fullLabel: String get() = if (name == null) dimensions else "$name · $dimensions"
}

private class NamedPaper(val name: String, val widthPt: Float, val heightPt: Float, val metric: Boolean)

private val NAMED_PAPERS = listOf(
    NamedPaper("Letter", 612f, 792f, metric = false),
    NamedPaper("A4", 595.28f, 841.89f, metric = true),
    NamedPaper("Legal", 612f, 1008f, metric = false)
)

/** PdfRenderer rounds page sizes to whole points. */
private const val PAPER_TOLERANCE_PT = 3f

/** Where paper is measured in inches. */
private val INCH_COUNTRIES = setOf("US", "LR", "MM")

/**
 * The paper a page of [widthPt] × [heightPt] PostScript points (1/72 inch) is: Letter, A4 or
 * Legal (either way round), or else its size in inches where paper is measured in inches
 * ([locale]) and in millimetres elsewhere.
 */
internal fun paperSizeOf(widthPt: Int, heightPt: Int, locale: Locale = Locale.getDefault()): PaperSize {
    val shorter = minOf(widthPt, heightPt).toFloat()
    val longer = maxOf(widthPt, heightPt).toFloat()
    val named = NAMED_PAPERS.firstOrNull {
        abs(it.widthPt - shorter) <= PAPER_TOLERANCE_PT && abs(it.heightPt - longer) <= PAPER_TOLERANCE_PT
    }
    val metric = named?.metric ?: (locale.country.uppercase(Locale.ROOT) !in INCH_COUNTRIES)
    val dimensions = if (metric) {
        "${millimetres(widthPt)} × ${millimetres(heightPt)} mm"
    } else {
        val format = DecimalFormat("0.##", DecimalFormatSymbols.getInstance(locale))
        "${format.format(widthPt / 72.0)} × ${format.format(heightPt / 72.0)} in"
    }
    val name = named?.let { if (widthPt > heightPt) "${it.name} landscape" else it.name }
    return PaperSize(name, dimensions)
}

private fun millimetres(points: Int): Int = (points * 25.4f / 72f).roundToInt()

/** The panel's document details, as far as they are known yet. */
private class PrintDetails(val pages: String, val paper: String, val summary: String)

private fun printDetails(preview: PrintPreview, load: PreviewLoad, locale: Locale): PrintDetails = when (load) {
    PreviewLoad.Opening -> PrintDetails(pages = "Checking…", paper = "Checking…", summary = preview.documentName)
    PreviewLoad.Failed -> PrintDetails(pages = "Unknown", paper = "Unknown", summary = preview.documentName)
    is PreviewLoad.Ready -> {
        val paper = paperSizeOf(load.info.firstPageWidthPt, load.info.firstPageHeightPt, locale)
        PrintDetails(
            pages = load.info.pageCount.toString(),
            paper = paper.fullLabel,
            summary = listOf(preview.documentName, pagesLabel(load.info.pageCount), paper.shortLabel).joinToString(" · ")
        )
    }
}

/** What the print screen knows about its PDF once it is open; sizes in PostScript points. */
internal data class PdfPreviewInfo(val pageCount: Int, val firstPageWidthPt: Int, val firstPageHeightPt: Int) {
    val firstPageAspect: Float
        get() = if (firstPageWidthPt > 0 && firstPageHeightPt > 0) {
            firstPageWidthPt.toFloat() / firstPageHeightPt
        } else {
            DEFAULT_PAGE_ASPECT
        }
}

private sealed interface PreviewLoad {
    data object Opening : PreviewLoad
    data class Ready(val info: PdfPreviewInfo) : PreviewLoad
    data object Failed : PreviewLoad
}

/**
 * The print screen's PDF, opened with [PdfRenderer]. PdfRenderer is not thread-safe and keeps only
 * one page open at a time, so everything it does happens on one background thread, under [lock],
 * a page at a time; results come back on the main thread. A request cancelled meanwhile (its page
 * scrolled away) is skipped if it hasn't started, and its bitmap recycled if it has.
 */
internal open class PdfPreviewDocument(private val file: File) {

    /** A pending open or render. Cancelled on the main thread, where results are delivered too. */
    class Request internal constructor() {
        @Volatile
        internal var cancelled = false
        internal var future: Future<*>? = null

        fun cancel() {
            cancelled = true
            future?.cancel(false)
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var closed = false

    /** Opens the PDF; [onOpened] gets its page count and first page's size, or null if it can't be shown. */
    open fun open(onOpened: (PdfPreviewInfo?) -> Unit): Request {
        val request = Request()
        request.future = executor.submit(Runnable {
            if (request.cancelled) return@Runnable
            val info = try {
                synchronized(lock) { openLocked() }
            } catch (e: Throwable) {
                // Not a PDF, password protected, or no PDF support (as in tests)
                Log.w(TAG, "Could not open the print preview", e)
                null
            }
            main.post { if (!request.cancelled) onOpened(info) }
        })
        return request
    }

    /** Renders page [index] [widthPx] wide; [onRendered] gets the bitmap, or null if it couldn't be. */
    open fun render(index: Int, widthPx: Int, onRendered: (Bitmap?) -> Unit): Request {
        val request = Request()
        request.future = executor.submit(Runnable {
            if (request.cancelled) return@Runnable
            val bitmap = try {
                synchronized(lock) { renderLocked(index, widthPx) }
            } catch (e: Throwable) {
                // A damaged page, or no memory for one this large
                Log.w(TAG, "Could not render page ${index + 1} of the print preview", e)
                null
            }
            main.post {
                if (request.cancelled) bitmap?.recycle() else onRendered(bitmap)
            }
        })
        return request
    }

    /** Closes the renderer and the file once any page being rendered is done. */
    open fun close() {
        executor.execute {
            synchronized(lock) {
                closed = true
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                renderer = null
                descriptor = null
            }
        }
    }

    private fun openLocked(): PdfPreviewInfo? {
        if (closed) return null
        val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val pdf = try {
            PdfRenderer(fd)
        } catch (e: Throwable) {
            runCatching { fd.close() }
            throw e
        }
        descriptor = fd
        renderer = pdf
        if (pdf.pageCount <= 0) return null
        val first = pdf.openPage(0)
        try {
            return PdfPreviewInfo(pdf.pageCount, first.width, first.height)
        } finally {
            first.close()
        }
    }

    private fun renderLocked(index: Int, widthPx: Int): Bitmap? {
        val pdf = renderer ?: return null
        if (closed || index !in 0 until pdf.pageCount) return null
        val page = pdf.openPage(index)
        try {
            val pageWidth = page.width.coerceAtLeast(1)
            val pageHeight = page.height.coerceAtLeast(1)
            var width = widthPx.coerceIn(1, MAX_RENDER_WIDTH_PX)
            var height = (width.toLong() * pageHeight / pageWidth).toInt().coerceAtLeast(1)
            val pixels = width.toLong() * height
            if (pixels > MAX_RENDER_PIXELS) {
                val scale = sqrt(MAX_RENDER_PIXELS.toDouble() / pixels)
                width = (width * scale).toInt().coerceAtLeast(1)
                height = (height * scale).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // Pages are transparent where nothing is drawn; paper is white
            bitmap.eraseColor(android.graphics.Color.WHITE)
            try {
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            } catch (e: RuntimeException) {
                bitmap.recycle()
                throw e
            }
            return bitmap
        } finally {
            page.close()
        }
    }

    private companion object {
        /** One thread for every preview, so no two pages are ever open in a renderer at once. */
        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RemBrowse print preview").apply { isDaemon = true }
        }
    }
}
