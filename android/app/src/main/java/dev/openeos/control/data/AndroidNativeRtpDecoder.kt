package dev.openeos.control.data

import android.media.MediaCodec

/**
 * Shared by production RTP and the real decoder/GLES acceptance test.
 *
 * Keep Android's decoder choice. Forcing the reference AVC decoder to work around
 * emulator color conversion regressed isolated first-picture output on API 34/36:
 * its display-order queue requires more input. A live session must not fabricate
 * another picture or EOS just to become ready. The original color failure remains
 * an acceptance blocker; see docs/validation/android-native-rtp-color-2026-10-20.md.
 */
internal fun createNativeRtpAvcDecoder(): MediaCodec = MediaCodec.createDecoderByType("video/avc")
