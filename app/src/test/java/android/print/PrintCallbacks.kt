package android.print

/**
 * The print framework's result callbacks have package-private constructors, so tests can only
 * subclass them from inside this package.
 */
fun layoutCallback(
    onFinished: (PrintDocumentInfo, Boolean) -> Unit = { _, _ -> },
    onCancelled: () -> Unit = {}
): PrintDocumentAdapter.LayoutResultCallback = object : PrintDocumentAdapter.LayoutResultCallback() {
    override fun onLayoutFinished(info: PrintDocumentInfo, changed: Boolean) = onFinished(info, changed)
    override fun onLayoutCancelled() = onCancelled()
}

fun writeCallback(
    onFinished: (Array<out PageRange>?) -> Unit = {},
    onFailed: (CharSequence?) -> Unit = {}
): PrintDocumentAdapter.WriteResultCallback = object : PrintDocumentAdapter.WriteResultCallback() {
    override fun onWriteFinished(pages: Array<out PageRange>?) = onFinished(pages)
    override fun onWriteFailed(error: CharSequence?) = onFailed(error)
}
