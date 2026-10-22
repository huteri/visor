package com.macedonga.visor.stage

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
import com.macedonga.visor.ui.animationsOff
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Where a screen wants the pair this frame: the stage's box on the window, and the pair with its pool inside it,
 * scaled about [pivotX], [pivotY] (fractions of the stage) and moved down by [shift].
 */
class StagePlace {
    var top = 0f
    var height = 0f
    var scale = 1f
    var pivotX = 0.5f
    var pivotY = 0.5f
    var shift = 0f
    var alpha = 1f

    /** The open stage folded [fold] of the way into a strip [stripPx] high, the pair [foldedScale] there and centred on [foldedCentre]. */
    fun folding(fullPx: Float, stripPx: Float, fold: Float, foldedScale: Float, foldedCentre: Float) {
        top = 0f
        height = fullPx - fold * (fullPx - stripPx)
        scale = 1f - (1f - foldedScale) * fold
        pivotX = 0.5f
        pivotY = 0.5f
        shift = fold * (foldedCentre - fullPx / 2)
        alpha = 1f
    }

    internal fun set(o: StagePlace) = mix(o, o, 0f)

    internal fun mix(a: StagePlace, b: StagePlace, t: Float) {
        fun l(x: Float, y: Float) = x + (y - x) * t
        top = l(a.top, b.top)
        height = l(a.height, b.height)
        scale = l(a.scale, b.scale)
        pivotX = l(a.pivotX, b.pivotX)
        pivotY = l(a.pivotY, b.pivotY)
        shift = l(a.shift, b.shift)
        alpha = l(a.alpha, b.alpha)
    }
}

/** A screen's hold on the pair while it is composed: where, whether the temples are closed, how restless it is. */
class Spot internal constructor(internal val place: StagePlace.() -> Unit) {
    var closed by mutableStateOf(true)
        internal set
    var liveliness by mutableStateOf(1f)
        internal set
}

/** A screen's wish for the light while it is composed. */
class LookRequest internal constructor(look: LightLook) {
    var look by mutableStateOf(look)
        internal set
}

/**
 * Shows the pair on this screen, placed by [place] every frame. The newest screen to ask has it; the pair travels
 * there from wherever it was, and [StageHolder.shown] says how far, for the screen's own things to follow.
 */
@Composable
fun StageSpot(closed: Boolean, liveliness: Float, place: StagePlace.() -> Unit): Spot {
    val stage = LocalStage.current
    val current = rememberUpdatedState(place)
    val spot = remember { Spot { current.value(this) }.also { it.closed = closed; it.liveliness = liveliness } }
    SideEffect {
        spot.closed = closed
        spot.liveliness = liveliness
    }
    DisposableEffect(spot) {
        stage.arrive(spot)
        onDispose { stage.leave(spot) }
    }
    return spot
}

/** Asks for the light to look like [look] while this is composed; the newest ask is the one shown. */
@Composable
fun StageLight(look: LightLook) {
    val stage = LocalStage.current
    val request = remember { LookRequest(look) }
    SideEffect { request.look = look }
    DisposableEffect(request) {
        stage.looks.add(request)
        onDispose { stage.looks.remove(request) }
    }
}

/** Dragging sideways turns the pair, and it springs back to rest. */
@Composable
fun Modifier.turnsThePair(onDetent: () -> Unit): Modifier {
    val stage = LocalStage.current
    val scope = rememberCoroutineScope()
    val detent = rememberUpdatedState(onDetent)
    return pointerInput(stage) {
        var lastDetent = 0
        val back = spring<Float>(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)
        detectHorizontalDragGestures(
            onDragEnd = { scope.launch { stage.yaw.animateTo(0f, back) } },
            onDragCancel = { scope.launch { stage.yaw.animateTo(0f, back) } },
        ) { change, dx ->
            change.consume()
            // Free for the first half turn either way, then it resists like a rubber band.
            val yaw = stage.yaw.value
            val next = (yaw + dx * DEGREES_PER_PX * (1f - (abs(yaw) / MAX_YAW).coerceIn(0f, 0.9f))).coerceIn(-MAX_YAW, MAX_YAW)
            scope.launch { stage.yaw.snapTo(next) }
            val d = (next / DETENT_DEGREES).toInt()
            if (d != lastDetent) {
                lastDetent = d
                detent.value()
            }
        }
    }
}

/**
 * The one stage: the pair, its pool and its light, made once and kept for the life of the activity under every
 * screen. Screens ask for it with [StageSpot] and leave holes where it shows; it goes where the newest one wants it,
 * so moving between screens never makes a second pair, a second pool or a second light. [shown] is false while the
 * screen on top has no pair: the stage fades out with the screen it was on.
 */
@Composable
fun SharedStage(stage: StageHolder, shown: Boolean) {
    val newest = stage.spots.lastOrNull()
    // The light runs while a screen asks for it; nothing ticks behind one that does not, or under the Live's picture.
    // While the mark holds the dark it stays down, and comes up as the mark lets go.
    val marked by remember { derivedStateOf { stage.intro.holding } }
    if (stage.looks.isNotEmpty()) {
        val look = stage.looks.lastOrNull()?.look ?: stage.restingLook
        AnimateLight(stage.light, if (marked) look.copy(level = 0f) else look)
    }
    SideEffect { stage.looks.lastOrNull()?.let { stage.restingLook = it.look } }
    Hinge(newest?.closed ?: stage.restingClosed, stage.pose, firstVisit = true, arrival = stage.intro::awaitLetGo)
    SideEffect { newest?.let { stage.restingClosed = it.closed } }
    LaunchedEffect(stage) {
        snapshotFlow { stage.travelling }.collect {
            while (stage.travelling) {
                val still = animationsOff()
                withFrameNanos { stage.step(it, still) }
            }
        }
    }
    LaunchedEffect(stage) { snapshotFlow { stage.yaw.value }.collect { stage.pose.yawDegrees = it; stage.renderer.invalidate() } }
    val curtain by animateFloatAsState(if (shown && newest != null) 1f else 0f, if (shown) snap() else tween(CURTAIN_MS), label = "stage")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val stageHeight = maxWidth / STAGE_ASPECT
        Box(
            Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, stage.placement().top.roundToInt()) }
                .layout { measurable, constraints ->
                    val h = stage.placement().height.roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(placeable.width, h) { placeable.place(0, 0) }
                }
                .clipToBounds()
                .graphicsLayer { alpha = curtain },
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .height(stageHeight)
                    .graphicsLayer {
                        val p = stage.placement()
                        scaleX = p.scale
                        scaleY = p.scale
                        transformOrigin = TransformOrigin(p.pivotX, p.pivotY)
                        translationY = p.shift
                        alpha = p.alpha
                        // Nothing is drawn while nothing of it shows: behind another screen, or under the Live's picture.
                        stage.renderer.paused = curtain == 0f || p.alpha <= 0f
                    },
            ) {
                LightPool(stage.light, Offset(0.5f, POOL_Y), Size(0.21f, 0.058f), Size(0.17f, 0.02f), Modifier.fillMaxSize())
                GlassesStage(liveliness = newest?.liveliness ?: 0.25f, active = curtain > 0f, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** How the pair travels from one screen's place to the next. */
internal val HANDOVER_EASE = CubicBezierEasing(0.2f, 0f, 0f, 1f)
internal const val HANDOVER_NS = 640_000_000L
private const val CURTAIN_MS = 180
private const val DEGREES_PER_PX = 0.32f
private const val MAX_YAW = 160f
private const val DETENT_DEGREES = 30f
