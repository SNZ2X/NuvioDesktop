package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import java.awt.Canvas
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.concurrent.Volatile

/**
 * Abstraction over the platform surface the native player renders into.
 * On Linux the video is rendered into memory and drawn by Compose
 * ([ComposeRenderSurfaceHost]); there is no AWT surface.
 */
internal interface NativePlayerSurfaceHost {
    fun isDisplayable(): Boolean
    var onPeerReady: (() -> Unit)?
    var onCursorActivity: (() -> Unit)?
    fun setControlsVisible(visible: Boolean)
    fun resetCursorVisibility()
    fun requestFocusInWindow(): Boolean
    fun noteCursorActivity()
    fun windowAncestor(): Window?
}

/**
 * Linux: no AWT surface; video frames are pulled into memory by the controller.
 *
 * Drives the OS cursor from the player's controls state: the cursor is hidden
 * (1x1 blank) while the controls overlay is hidden during playback and shown
 * again on any mouse movement or when the controls reappear.
 *
 * Ported from JJDizz1L/NuvioLinux (GPL-3.0), derived from
 * NuvioMedia/NuvioDesktop feat/hwaccel-libmpv-linux.
 */
internal class ComposeRenderSurfaceHost : NativePlayerSurfaceHost {
    private val log = Logger.withTag("ComposeVideoSurface")

    @Volatile
    private var window: Window? = null

    @Volatile
    private var controlsVisible = true

    private var blankCursor: Cursor? = null

    override fun isDisplayable(): Boolean = true
    override var onPeerReady: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null

    override fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        applyCursor(showCursor = visible)
    }

    override fun resetCursorVisibility() {
        applyCursor(showCursor = controlsVisible)
    }

    override fun requestFocusInWindow(): Boolean = currentWindow()?.requestFocusInWindow() ?: false

    override fun noteCursorActivity() {
        // Reveal the cursor on any mouse movement; the controls auto-hide
        // timer hides it again together with the controls overlay.
        applyCursor(showCursor = true)
        onCursorActivity?.invoke()
    }

    override fun windowAncestor(): Window? = currentWindow()

    /** Attaches the AWT window whose cursor this host controls. */
    fun attachWindow(w: Window) {
        window = w
        applyCursor(showCursor = controlsVisible)
    }

    /** Restores the default cursor when the window loses focus (and re-applies
     * the hidden state when focus returns). */
    fun onWindowFocusChanged(gained: Boolean) {
        if (gained) {
            resetCursorVisibility()
        } else {
            applyCursor(showCursor = true)
        }
    }

    private fun currentWindow(): Window? {
        window?.let { return it }
        val found = Window.getOwnerlessWindows()
            .firstOrNull { it.isVisible && it.isDisplayable && it.isShowing }
        if (found != null) window = found
        return found
    }

    private fun applyCursor(showCursor: Boolean) {
        val w = currentWindow() ?: return
        val cursor = if (showCursor) Cursor.getDefaultCursor() else blankCursor()
        if (SwingUtilities.isEventDispatchThread()) {
            w.cursor = cursor
        } else {
            SwingUtilities.invokeLater { w.cursor = cursor }
        }
    }

    private fun blankCursor(): Cursor {
        blankCursor?.let { return it }
        val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val cursor = Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-blank")
        blankCursor = cursor
        return cursor
    }
}

internal class NativePlayerHost : Canvas(), NativePlayerSurfaceHost {
    override var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    override var onCursorActivity: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    private var controlsVisible = true
    private var cursorVisible = true

    private companion object {
        val hiddenCursor: Cursor by lazy {
            val image = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            Toolkit.getDefaultToolkit().createCustomCursor(image, Point(0, 0), "nuvio-hidden-cursor")
        }
    }

    init {
        background = Color.BLACK
        ignoreRepaint = false
        addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                noteCursorActivity()
            }

            override fun mouseDragged(event: MouseEvent) {
                noteCursorActivity()
            }
        })
    }

    override fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        setCursorVisible(visible)
    }

    override fun noteCursorActivity() {
        onCursorActivity?.invoke()
    }

    override fun resetCursorVisibility() {
        controlsVisible = true
        setCursorVisible(true)
    }

    // isDisplayable() is inherited from Canvas (Component) and satisfies the
    // interface member with the same Java-method signature.

    override fun requestFocusInWindow(): Boolean = super.requestFocusInWindow()

    override fun windowAncestor(): Window? = SwingUtilities.getWindowAncestor(this)

    private fun setCursorVisible(visible: Boolean) {
        if (cursorVisible == visible) return
        cursorVisible = visible
        cursor = if (visible) Cursor.getDefaultCursor() else hiddenCursor
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        resetCursorVisibility()
        super.removeNotify()
    }
}
