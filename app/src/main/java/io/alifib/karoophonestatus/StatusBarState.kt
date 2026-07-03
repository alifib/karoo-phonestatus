package io.alifib.karoophonestatus

/**
 * Process-shared bridge between [StatusBarMonitorService] (an accessibility
 * service that reads the real systemui status bar) and
 * [PhoneStatusOverlayService] (which draws our icon). Both run in the app's
 * main process, so a plain singleton is enough.
 */
object StatusBarState {

    /** Whether the native status bar's right-hand icon cluster is on screen. */
    @Volatile
    var barShown: Boolean = false
        private set

    /** Left edge (screen px) of the left-most right-cluster icon (e.g. wifi). */
    @Volatile
    var anchorLeftPx: Int = 0
        private set

    /** Top edge (screen px) of the anchor icon's box; the overlay centers on it. */
    @Volatile
    var barTopPx: Int = 0
        private set

    /** Height (px) of the anchor icon's box; the overlay centers within it. */
    @Volatile
    var barHeightPx: Int = 0
        private set

    /** Package name of the app drawing the status bar we are anchored to. */
    @Volatile
    var packageName: String? = null
        private set

    @Volatile
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) {
        listener = l
    }

    fun update(shown: Boolean, anchorLeft: Int, barTop: Int, barHeight: Int, pkg: String?) {
        val changed = shown != barShown ||
            anchorLeft != anchorLeftPx ||
            barTop != barTopPx ||
            barHeight != barHeightPx ||
            pkg != packageName
        barShown = shown
        anchorLeftPx = anchorLeft
        barTopPx = barTop
        barHeightPx = barHeight
        packageName = pkg
        if (changed) listener?.invoke()
    }
}
