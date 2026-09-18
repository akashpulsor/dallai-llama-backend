package com.dalai.llama.postprod.service.clip;

import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor.ConcatInput;
import com.dalai.llama.postprod.service.clip.FfmpegClipProcessor.ShotDurations;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bringing one shot to the profile every other shot is brought to.
 *
 * <p>This runs once per shot, sequentially, instead of opening every input at once in a single
 * filter_complex. The point is memory: one decoder and one encoder live at a time, so a thirty-shot
 * film costs the same as a thirteen-shot one. Total encoding work is unchanged -- each frame is
 * still encoded exactly once -- so the bounded memory is paid for in temp disk, not in time.
 *
 * <p>What is asserted is the part that makes the second stage a COPY. If normalising leaves any
 * property un-forced, the copy join that follows either refuses or produces a file that plays for
 * two seconds and stops.
 */
class NormaliseForJoinTest {

    private static final int W = 1080;
    private static final int H = 1920;
    private final FfmpegClipProcessor ffmpeg = new FfmpegClipProcessor(600, 30);

    private static ConcatInput withAudio() {
        return new ConcatInput("h264", "yuv420p", "24/1", W, H, "aac", "44100", "2");
    }

    /** A shot whose line fits its picture exactly -- nothing to hold, nothing to pad. */
    private static ShotDurations even() {
        return new ShotDurations(4.0, 4.0);
    }

    private static ConcatInput silent() {
        return new ConcatInput("h264", "yuv420p", "24/1", W, H, null, null, null);
    }

    private static String flagValue(List<String> command, String flag) {
        int at = command.indexOf(flag);
        return at < 0 || at + 1 >= command.size() ? null : command.get(at + 1);
    }

    @Test
    void forcesTheFrameRateSoTheCopyJoinIsPossible() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));

        // A 24fps and a 30fps clip cannot be copy-joined however well they match otherwise.
        assertEquals("30", flagValue(command, "-r"));
        assertEquals("cfr", flagValue(command, "-fps_mode"), "variable frame rate breaks a copy join");
        assertEquals("libx264", flagValue(command, "-c:v"));
        assertEquals("yuv420p", flagValue(command, "-pix_fmt"));
        assertEquals("aac", flagValue(command, "-c:a"));
        assertEquals("48000", flagValue(command, "-ar"));
        assertEquals("2", flagValue(command, "-ac"));
    }

    @Test
    void givesASilentShotARealAudioTrack() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/silent.mp4", silent(), even(), W, H, Path.of("out.mp4"));

        // Silence has to be a track, not an absent one: one silent shot among thirteen with sound
        // breaks the join outright.
        assertTrue(command.contains("anullsrc=channel_layout=stereo:sample_rate=48000"),
                "a silent shot must be given generated silence");
        // Two -map flags: picture from the shot, sound from the generated silence.
        assertEquals("0:v:0", flagValue(command, "-map"), "picture still comes from the shot");
        assertTrue(command.contains("1:a:0"), "audio must come from the generated silence");
        assertFalse(command.contains("0:a:0"), "there is no audio on this shot to take");
        assertTrue(command.contains("-shortest"), "anullsrc never ends on its own");
        assertNull(flagValue(command, "-af"),
                "there is no real track here to put on a common time base");
    }

    @Test
    void takesAudioFromTheShotWhenItHasSome() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));

        assertTrue(command.contains("0:a:0"), "audio must come from the shot itself");
        assertFalse(command.stream().anyMatch(arg -> arg.startsWith("anullsrc")));
    }

    /**
     * The shot's streams must come out the SAME LENGTH, or the film drifts.
     *
     * <p>A copy-concat offsets each shot by the longest stream in the one before it. A clip whose
     * audio runs 365ms past its picture therefore pushes sound ahead of picture, and it compounds:
     * measured at 25.825s of video against 26.211s of audio across six shots. apad runs the track on
     * as silence, -shortest cuts it at the picture, and aresample puts it on a common time base --
     * all three are rules this class already documents.
     */
    @Test
    void equalisesAudioAndVideoLengthSoTheFilmDoesNotDrift() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));

        String audioFilter = flagValue(command, "-af");
        assertTrue(audioFilter != null && audioFilter.contains("apad"),
                "audio must be padded to reach the picture, was: " + audioFilter);
        assertTrue(audioFilter.contains("aresample=async=1:first_pts=0"),
                "without a common time base a shot drifts a little further with every join");
        assertTrue(command.contains("-shortest"),
                "padded audio runs for ever unless it is cut at the picture");
    }

    @Test
    void padsRatherThanCropsToReachTheTargetSize() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));

        String filter = flagValue(command, "-vf");
        assertTrue(filter.contains("force_original_aspect_ratio=decrease"), filter);
        assertTrue(filter.contains("pad=" + W + ":" + H), filter);
        assertTrue(filter.contains("setsar=1"), "an unset SAR is another way a copy join fails");
    }

    /**
     * A line that runs past its picture must not be cut short.
     *
     * <p>Equalising the streams by padding audio and cutting at the picture deletes the end of every
     * sentence. video-generation-service already refuses that trade by default -- its dialogueFit
     * EXTEND "gives the shot the seconds the line needs, so nothing is cut" -- and holding the last
     * frame gives the same answer here, with both streams still ending together.
     */
    @Test
    void holdsThePictureWhenTheLineRunsLong() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), new ShotDurations(4.0, 4.365), W, H, Path.of("out.mp4"));

        String filter = flagValue(command, "-vf");
        assertTrue(filter.contains("tpad=stop_mode=clone:stop_duration=0.365"),
                "the shot should hold its last frame for the overhang, was: " + filter);
    }

    /** The other direction: picture longer than the line, so the track is padded and cut at it. */
    @Test
    void padsTheLineWhenThePictureRunsLong() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), new ShotDurations(4.5, 3.0), W, H, Path.of("out.mp4"));

        assertFalse(flagValue(command, "-vf").contains("tpad"),
                "there is no overhang to hold here");
        assertTrue(flagValue(command, "-af").contains("apad"));
        assertTrue(command.contains("-shortest"));
    }

    /** Milliseconds are not worth a filter. */
    @Test
    void ignoresATrivialOverhang() {
        List<String> command = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), new ShotDurations(4.000, 4.010), W, H, Path.of("out.mp4"));

        assertFalse(flagValue(command, "-vf").contains("tpad"));
    }

    /** ffmpeg rejects http options outright on a local path, and stage two reads local files. */
    @Test
    void reconnectOptionsOnlyOnHttpInputs() {
        List<String> remote = ffmpeg.buildNormaliseCommand(
                "https://minio/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));
        List<String> local = ffmpeg.buildNormaliseCommand(
                "/tmp/a.mp4", withAudio(), even(), W, H, Path.of("out.mp4"));

        assertTrue(remote.contains("-reconnect"));
        assertFalse(local.contains("-reconnect"));
    }
}
