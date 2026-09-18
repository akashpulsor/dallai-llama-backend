package com.dalai.llama.postprod.service.clip;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading ffprobe output without guessing which stream a field belonged to.
 *
 * <p>This exists because of a real film that came out silent. The first version asked for
 * codec_type and codec_name together and walked the lines assuming codec_type arrived first. It does
 * not -- ffprobe emits codec_name BEFORE codec_type:
 *
 * <pre>
 *   codec_name=h264
 *   codec_type=video
 *   codec_name=aac
 *   codec_type=audio
 * </pre>
 *
 * <p>So every codec_name was attributed to the PREVIOUS stream's type. Production logged
 * {@code videoCodec=aac, audioCodec=null} for a clip that plainly had both, and a null audio codec
 * makes a shot look silent -- so each one was normalised against generated silence and its real
 * dialogue was dropped on the floor.
 *
 * <p>The fix is -select_streams, so each probe returns one stream and nothing has to be inferred.
 * These fixtures are literal ffprobe 7.1 output.
 */
class ProbeParsingTest {

    private static final String VIDEO_STREAM = """
            codec_name=h264
            width=1080
            height=1920
            pix_fmt=yuv420p
            r_frame_rate=24/1
            """;

    private static final String AUDIO_STREAM = """
            codec_name=aac
            sample_rate=48000
            channels=2
            """;

    @Test
    void readsTheVideoStreamsOwnFields() {
        assertEquals("h264", FfmpegClipProcessor.streamValue(VIDEO_STREAM, "codec_name"));
        assertEquals("yuv420p", FfmpegClipProcessor.streamValue(VIDEO_STREAM, "pix_fmt"));
        assertEquals("24/1", FfmpegClipProcessor.streamValue(VIDEO_STREAM, "r_frame_rate"),
                "the audio stream's 0/0 must never reach this field");
        assertEquals("1080", FfmpegClipProcessor.streamValue(VIDEO_STREAM, "width"));
    }

    @Test
    void readsTheAudioStreamsOwnFields() {
        assertEquals("aac", FfmpegClipProcessor.streamValue(AUDIO_STREAM, "codec_name"),
                "aac is the AUDIO codec -- attributing it to video is the bug this guards");
        assertEquals("48000", FfmpegClipProcessor.streamValue(AUDIO_STREAM, "sample_rate"));
        assertEquals("2", FfmpegClipProcessor.streamValue(AUDIO_STREAM, "channels"));
    }

    /** A shot with no audio: ffprobe returns nothing at all, and that has to read as "no track". */
    @Test
    void anAbsentStreamIsNull() {
        assertNull(FfmpegClipProcessor.streamValue("", "codec_name"));
        assertNull(FfmpegClipProcessor.streamValue(null, "codec_name"));
    }

    /** A field that is present but unknown is not a value. */
    @Test
    void notApplicableIsNull() {
        assertNull(FfmpegClipProcessor.streamValue("sample_rate=N/A\n", "sample_rate"));
    }

    @Test
    void doesNotMatchOnAPrefix() {
        assertNull(FfmpegClipProcessor.streamValue(VIDEO_STREAM, "codec"),
                "codec must not match codec_name");
    }
}
