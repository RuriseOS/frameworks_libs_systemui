/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.wallpaper.weathereffects.graphics.snow

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.util.SizeF
import androidx.core.graphics.createBitmap
import com.google.android.wallpaper.weathereffects.graphics.FrameBuffer
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffect.Companion.DEFAULT_INTENSITY
import com.google.android.wallpaper.weathereffects.graphics.WeatherEffectBase
import com.google.android.wallpaper.weathereffects.graphics.utils.GraphicsUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MathUtils
import com.google.android.wallpaper.weathereffects.graphics.utils.MatrixUtils.getScale
import com.google.android.wallpaper.weathereffects.graphics.utils.TimeUtils
import java.util.concurrent.Executor
import kotlin.math.abs

/** Defines and generates the rain weather effect animation. */
class SnowEffect(
    /** The config of the snow effect. */
    private val snowConfig: SnowEffectConfig,
    foreground: Bitmap,
    background: Bitmap,
    private var intensity: Float = DEFAULT_INTENSITY,
    /** The initial size of the surface where the effect will be shown. */
    private var surfaceSize: SizeF,
    /** App main executor. */
    private val mainExecutor: Executor,
) : WeatherEffectBase(foreground, background, surfaceSize) {

    private var snowSpeed: Float = 0.8f
    private val snowPaint = Paint().also { it.shader = snowConfig.colorGradingShader }

    // Use outlineFrameBuffer and outlineFrameBufferPaint to get foreground outline
    // its process requires blur effects
    private var outlineFrameBuffer = FrameBuffer(background.width, background.height)
    private val outlineFrameBufferPaint =
        Paint().also { it.shader = snowConfig.accumulatedSnowOutlineShader }
    // accumulationFrameBuffer and accumulationFrameBufferPaint will get the result from
    // outlineFrameBuffer and add noise to snow fluffiness
    private var accumulationFrameBuffer =
        FrameBuffer(
            (background.width * bitmapScale).toInt(),
            (background.height * bitmapScale).toInt(),
        )
    private val accumulationFrameBufferPaint =
        Paint().also { it.shader = snowConfig.accumulatedSnowResultShader }

    init {
        outlineFrameBuffer.setRenderEffect(
            RenderEffect.createBlurEffect(
                BLUR_RADIUS / bitmapScale,
                BLUR_RADIUS / bitmapScale,
                Shader.TileMode.CLAMP,
            )
        )
        updateTextureUniforms()
        adjustCropping(surfaceSize)
        prepareColorGrading()
        updateGridSize(surfaceSize)
        setIntensity(intensity)

        // Generate accumulated snow at the end after we updated all the uniforms.
        generateAccumulatedSnow()
    }

    override fun update(deltaMillis: Long, frameTimeNanos: Long) {
        elapsedTime += snowSpeed * TimeUtils.millisToSeconds(deltaMillis)

        snowConfig.shader.setFloatUniform("time", elapsedTime)
        snowConfig.colorGradingShader.setInputShader("texture", snowConfig.shader)
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPaint(snowPaint)
    }

    override fun release() {
        super.release()
        outlineFrameBuffer.close()
        accumulationFrameBuffer.close()
    }

    override fun setIntensity(intensity: Float) {
        super.setIntensity(intensity)
        /**
         * Increase effect speed as weather intensity decreases. This compensates for the floaty
         * appearance when there are fewer particles at the original speed.
         */
        if (this.intensity != intensity) {
            snowSpeed = MathUtils.map(intensity, 0f, 1f, 2.5f, 1.7f)
            this.intensity = intensity
        }
    }

    override fun setBitmaps(foreground: Bitmap?, background: Bitmap): Boolean {
        if (!super.setBitmaps(foreground, background)) {
            return false
        }

        outlineFrameBuffer.close()
        accumulationFrameBuffer.close()
        outlineFrameBuffer = FrameBuffer(background.width, background.height)
        val newScale = getScale(parallaxMatrix)
        bitmapScale = newScale
        accumulationFrameBuffer =
            FrameBuffer(
                (background.width * bitmapScale).toInt(),
                (background.height * bitmapScale).toInt(),
            )
        outlineFrameBuffer.setRenderEffect(
            RenderEffect.createBlurEffect(
                BLUR_RADIUS / bitmapScale,
                BLUR_RADIUS / bitmapScale,
                Shader.TileMode.CLAMP,
            )
        )
        // GenerateAccumulatedSnow needs foreground for accumulatedSnowShader, and needs frameBuffer
        // which is also changed with background
        generateAccumulatedSnow()
        return true
    }

    override val shader: RuntimeShader
        get() = snowConfig.shader

    override val colorGradingShader: RuntimeShader
        get() = snowConfig.colorGradingShader

    override val lut: Bitmap?
        get() = snowConfig.lut

    override val colorGradingIntensity: Float
        get() = snowConfig.colorGradingIntensity

    override fun setMatrix(matrix: Matrix) {
        val oldScale = bitmapScale
        super.setMatrix(matrix)
        // Blur radius should change with scale because it decides the fluffiness of snow
        if (abs(bitmapScale - oldScale) > FLOAT_TOLERANCE) {
            outlineFrameBuffer.close()
            accumulationFrameBuffer.close()
            outlineFrameBuffer = FrameBuffer((background.width), (background.height))

            outlineFrameBuffer.setRenderEffect(
                RenderEffect.createBlurEffect(
                    BLUR_RADIUS / bitmapScale,
                    BLUR_RADIUS / bitmapScale,
                    Shader.TileMode.CLAMP,
                )
            )
            accumulationFrameBuffer =
                FrameBuffer(
                    (background.width * bitmapScale).toInt(),
                    (background.height * bitmapScale).toInt(),
                )
            snowConfig.shader.setInputShader(
                "accumulatedSnow",
                BitmapShader(blankBitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            )

            generateAccumulatedSnow()
        }
    }

    override fun updateTextureUniforms() {
        super.updateTextureUniforms()
        snowConfig.accumulatedSnowResultShader.setInputBuffer(
            "noise",
            BitmapShader(snowConfig.noiseTexture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT),
        )
    }

    private fun prepareColorGrading() {
        snowConfig.colorGradingShader.setInputShader("texture", snowConfig.shader)
        snowConfig.lut?.let {
            snowConfig.colorGradingShader.setInputShader(
                "lut",
                BitmapShader(it, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
            )
        }
    }

    // Generate accumulated snow requires two passes, first is to generate blurred foreground
    // outline, second is to add snow fluffiness to it.
    // It should only be called when bitmaps or screensize change, and should not be called
    // per frame.
    private fun generateAccumulatedSnow() {
        // Generate foreground outline
        val renderingCanvas = outlineFrameBuffer.beginDrawing()
        snowConfig.accumulatedSnowOutlineShader.setFloatUniform("scale", bitmapScale)
        snowConfig.accumulatedSnowOutlineShader.setFloatUniform(
            "snowThickness",
            SNOW_THICKNESS / bitmapScale,
        )
        snowConfig.accumulatedSnowOutlineShader.setFloatUniform("screenWidth", surfaceSize.width)
        snowConfig.accumulatedSnowOutlineShader.setInputBuffer(
            "foreground",
            BitmapShader(foreground, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )

        renderingCanvas.drawPaint(outlineFrameBufferPaint)
        outlineFrameBuffer.endDrawing()

        outlineFrameBuffer.tryObtainingImage(
            ::generateAccumulatedSnowWithBlurredOutline,
            mainExecutor,
        )
    }

    /** @param outlineImage is generated by outlineShader */
    private fun generateAccumulatedSnowWithBlurredOutline(outlineImage: Bitmap) {
        val renderingCanvas = accumulationFrameBuffer.beginDrawing()
        snowConfig.accumulatedSnowResultShader.setInputBuffer(
            "foregroundOutline",
            BitmapShader(outlineImage, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR),
        )
        // Actually, we should not generate it with bitmap
        snowConfig.accumulatedSnowResultShader.setFloatUniform(
            "transformMatrixBitmapScaleOnly",
            transformMatrixCenterCrop,
        )
        renderingCanvas.drawPaint(accumulationFrameBufferPaint)
        accumulationFrameBuffer.endDrawing()

        accumulationFrameBuffer.tryObtainingImage(
            { image ->
                snowConfig.shader.setInputBuffer(
                    "accumulatedSnow",
                    BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                )
                outlineFrameBuffer.close()
            },
            mainExecutor,
        )
    }

    override fun updateGridSize(newSurfaceSize: SizeF) {
        val gridSize = GraphicsUtils.computeDefaultGridSize(newSurfaceSize, snowConfig.pixelDensity)
        snowConfig.shader.setFloatUniform("gridSize", 7 * gridSize, 2f * gridSize)
    }

    companion object {
        const val BLUR_RADIUS = 4f
        // Use blur effect for both blurring the snow accumulation and generating a gradient edge
        // so that intensity can control snow thickness by cut the gradient edge in snow_effect
        // shader.
        const val SNOW_THICKNESS = 6f
        // During wallpaper resizing, the updated accumulation texture might not be immediately
        // available.
        // To prevent displaying outdated accumulation, we use a tiny blank bitmap to temporarily
        // clear the rendering area before the new texture is ready.
        private val blankBitmap = createBitmap(1, 1)
    }
}
