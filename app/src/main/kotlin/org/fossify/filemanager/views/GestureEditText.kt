package org.fossify.filemanager.views

import android.content.Context
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import com.alexvasilkov.gestures.GestureController
import com.alexvasilkov.gestures.State
import com.alexvasilkov.gestures.views.interfaces.GestureView
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.filemanager.extensions.config

// inspired by
// https://github.com/alexvasilkov/GestureViews/blob/f0a4c266e31dcad23bd0d9013531bc1c501b9c9f/sample/src/main/java/com/alexvasilkov/gestures/sample/ex/custom/text/GestureTextView.java
class GestureEditText : AppCompatEditText, GestureView {
    constructor(context: Context) : super(context)

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    constructor(context: Context, attrs: AttributeSet, defStyle: Int) : super(context, attrs, defStyle)

    private val controller: GestureController = object : GestureController(this) {
        // EditText has its own long-press handling for text selection. The gesture
        // controller's default implementation calls performLongClick() as well,
        // which competes with TextView's selection gesture and can dismiss the
        // selection on the first attempt.
        override fun onLongPress(event: MotionEvent) {
            // Keep long-press handling exclusively in AppCompatEditText.
        }
    }
    private var origSize = 0f
    private var size = 0f

    init {
        controller.settings.setOverzoomFactor(1f).isPanEnabled = false
        controller.addOnStateChangeListener(object : GestureController.OnStateChangeListener {
            override fun onStateChanged(state: State) {
                applyState(state)
            }

            override fun onStateReset(oldState: State, newState: State) {
                applyState(newState)
            }
        })

        origSize = textSize
        // TextView shows the keyboard right after a long-press selection starts. The keyboard
        // animation resizes the window and cancels that fresh selection (seen in the logs:
        // showSoftInput() 57 ms after the selection started), so it only worked on the second try,
        // when the keyboard was already up. The keyboard is shown manually on a plain tap instead.
        setShowSoftInputOnFocus(false)
        setTextColor(context.getProperTextColor())
        setLinkTextColor(context.getProperPrimaryColor())

        val storedTextZoom = context.config.editorTextZoom
        if (storedTextZoom != 0f) {
            onGlobalLayout {
                controller.state.zoomTo(storedTextZoom, width / 2f, height / 2f)
                controller.updateState()
            }
        }
    }

    override fun getController() = controller

    // Primitive state only: onSelectionChanged() can be called from the super constructor.
    private var lastSelectionTime = 0L
    private var lastSelectionTextLength = -1
    private var selectionRetried = false
    private var gestureInProgress = false
    private var longPressed = false
    private var moved = false
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selectionRetried = false
                lastSelectionTime = 0L
                longPressed = false
                moved = false
                downX = event.x
                downY = event.y
            }

            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                    moved = true
                }
            }
        }

        // Let EditText handle the event first (selection / long-press / cursor).
        val handled = super.onTouchEvent(event)

        // Plain tap that placed the cursor: show the keyboard (not done for long-press/double-tap
        // selections, as the resize would cancel them).
        if (event.actionMasked == MotionEvent.ACTION_UP && !longPressed && !moved &&
            event.pointerCount == 1 && !gestureInProgress && isFocused && !hasSelection()
        ) {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, 0)
        }

        // The gesture controller is only needed for pinch-to-zoom. Feeding it single-finger
        // touches interferes with the text selection that starts on the first long-press,
        // so it only receives multi-touch gestures.
        if (event.pointerCount > 1) {
            gestureInProgress = true
        }

        if (gestureInProgress) {
            // GestureController enables its own long-press detector from isLongClickable on
            // every touch; keep it off so it can't compete with TextView's selection.
            val wasLongClickable = isLongClickable
            isLongClickable = false
            try {
                controller.onTouch(this, event)
            } finally {
                isLongClickable = wasLongClickable
            }

            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureInProgress = false
            }
        }

        return handled
    }

    override fun performLongClick(): Boolean {
        longPressed = true
        return super.performLongClick()
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (selStart != selEnd) {
            lastSelectionTime = System.currentTimeMillis()
            lastSelectionTextLength = text?.length ?: -1
        } else if (lastSelectionTime != 0L && !selectionRetried) {
            // A selection that was just created by a long-press collapsed by itself, without the
            // user touching the screen again (the keyboard showing / layout changing on the first
            // attempt cancels it). Repeat the long-press, which is what a second attempt does.
            val age = System.currentTimeMillis() - lastSelectionTime
            lastSelectionTime = 0L
            // Typing over the selection also collapses it; that must not trigger a retry.
            if (age < 1500 && lastSelectionTextLength == (text?.length ?: -1)) {
                selectionRetried = true
                postDelayed({
                    if (isAttachedToWindow && !hasSelection()) {
                        performLongClick()
                    }
                }, 350)
            }
        }
    }

    override fun setTextSize(size: Float) {
        super.setTextSize(size)
        origSize = textSize
        applyState(controller.state)
    }

    override fun setTextSize(unit: Int, size: Float) {
        super.setTextSize(unit, size)
        origSize = textSize
        applyState(controller.state)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        controller.settings.setViewport(width, height).setImage(width, height)
        // Height-only changes (keyboard showing/hiding) must not touch the gesture state,
        // otherwise an in-progress text selection can be dismissed.
        if (width != oldWidth) {
            controller.updateState()
        }
    }

    private fun applyState(state: State) {
        var size = origSize * state.zoom
        val maxSize = origSize * controller.stateController.getMaxZoom(state)
        size = Math.max(origSize, Math.min(size, maxSize))

        // Don't round (and thus don't change) the size at the default zoom level.
        size = if (State.equals(state.zoom, 1f)) origSize else Math.round(size).toFloat()

        // Compare against the real current text size. Previously the cached value started at 0,
        // so the very first gesture state change (the first touch) called setTextSize() even though
        // nothing had changed. That re-laid out the text and cancelled the just-started selection.
        if (!State.equals(textSize, size)) {
            this.size = size
            super.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
            context.config.editorTextZoom = state.zoom
        }
    }
}