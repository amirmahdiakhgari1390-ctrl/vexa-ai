package com.vexa.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * موتور ساخت ویدیو واقعی MP4 روی گوشی (بدون محدودیت تعداد فیلم):
 * - هر بار یک فایل اختصاصی با شناسه یکتا تولید می‌کند تا ساخت فیلم دوم، سوم و... هیچ تداخلی نداشته باشد
 */
object VideoGenerator {

    @Synchronized
    fun createCinematicMp4(
        ctx: Context,
        scenes: List<Bitmap>,
        titleOverlay: String
    ): FileGenerator.GeneratedFile? {
        if (scenes.isEmpty()) return null

        val width = 720
        val height = 720
        val fps = 24
        val secondsPerScene = 3
        val framesPerScene = fps * secondsPerScene
        val bitRate = 2_000_000

        val outDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VexaVideos")
        if (!outDir.exists()) outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val uniqueSuffix = UUID.randomUUID().toString().take(6)
        val fileName = "Vexa_Video_${stamp}_$uniqueSuffix.mp4"
        val outFile = File(outDir, fileName)

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null

        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder.createInputSurface()
            encoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(150, 5, 8, 17)
            }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22D3EE")
                textSize = 24f
                isFakeBoldText = true
            }

            val totalFrames = scenes.size * framesPerScene
            val frameDurationUs = 1_000_000L / fps

            for (frameIdx in 0 until totalFrames) {
                val sceneIdx = (frameIdx / framesPerScene).coerceAtMost(scenes.size - 1)
                val localFrame = frameIdx % framesPerScene
                val progress = localFrame.toFloat() / framesPerScene.toFloat()
                val bmp = scenes[sceneIdx]

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)

                    val baseScale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                    val zoomFactor = if (sceneIdx % 2 == 0) {
                        1.0f + 0.14f * progress
                    } else {
                        1.14f - 0.14f * progress
                    }
                    val scale = baseScale * zoomFactor
                    val dx = (width - bmp.width * scale) * 0.5f + (if (sceneIdx % 2 == 0) -18f * progress else 18f * progress)
                    val dy = (height - bmp.height * scale) * 0.5f

                    val matrix = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(dx, dy)
                    }
                    canvas.drawBitmap(bmp, matrix, paint)

                    canvas.drawRect(Rect(0, height - 54, width, height), badgeBgPaint)
                    val cleanTitle = titleOverlay.take(42)
                    canvas.drawText("VEXA AI  •  $cleanTitle", 24f, height - 20f, textPaint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                while (true) {
                    val status = encoder.dequeueOutputBuffer(bufferInfo, 2000)
                    if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    } else if (status >= 0) {
                        val encodedData = encoder.getOutputBuffer(status)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                bufferInfo.presentationTimeUs = frameIdx * frameDurationUs
                                encodedData.position(bufferInfo.offset)
                                encodedData.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                            }
                        }
                        encoder.releaseOutputBuffer(status, false)
                    }
                }
            }

            encoder.signalEndOfInputStream()
            while (true) {
                val status = encoder.dequeueOutputBuffer(bufferInfo, 5000)
                if (status < 0) break
                val encodedData = encoder.getOutputBuffer(status)
                if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                    }
                }
                encoder.releaseOutputBuffer(status, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }

            if (outFile.exists() && outFile.length() > 1000L) {
                FileGenerator.GeneratedFile(outFile, "video/mp4", fileName)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            try { inputSurface?.release() } catch (_: Exception) { }
            try { encoder?.stop(); encoder?.release() } catch (_: Exception) { }
            try { if (muxer != null) { muxer.stop(); muxer.release() } } catch (_: Exception) { }
        }
    }
}
